package za.redbridge.experiment;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import org.encog.Encog;
import org.encog.ml.ea.train.basic.TrainEA;
import org.encog.neural.hyperneat.substrate.Substrate;
import org.encog.neural.neat.NEATNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Scanner;

import za.redbridge.experiment.HyperNEATM.HyperNEATMCODEC;
import za.redbridge.experiment.NEATM.NEATMNetwork;
import za.redbridge.experiment.NEATM.NEATMPopulation;
import za.redbridge.experiment.NEATM.NEATMUtil;
import za.redbridge.experiment.NEATM.sensor.SensorMorphology;
import za.redbridge.experiment.NEAT.NEATPopulation;
import za.redbridge.experiment.NEAT.NEATUtil;
import za.redbridge.experiment.HyperNEATM.SubstrateFactory;
import za.redbridge.simulator.config.SimConfig;


import static za.redbridge.experiment.Utils.isBlank;
import static za.redbridge.experiment.Utils.readObjectFromFile;

/**
 * Entry point for the experiment platform.
 * <p>
 * Created by jamie on 2014/09/09.
 */
public class Main
{

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final double CONVERGENCE_SCORE = 110;

    public static void main(String[] args) throws IOException
    {
        Args options = new Args();
        new JCommander(options, args);

        log.info(options.toString());

        SimConfig simConfig;
        if (!isBlank(options.configFile))                   // if using config file
        {
            simConfig = new SimConfig(options.configFile);
        } else
        {
            simConfig = new SimConfig();
        }

        // Load the morphology
        SensorMorphology morphology = null;
        if (options.control)
        {
            if (!isBlank(options.morphologyPath))
            {
                NEATMNetwork network = (NEATMNetwork) readObjectFromFile(options.morphologyPath);
                morphology = network.getSensorMorphology();
            } else
            {
                morphology = new KheperaIIIMorphology();
            }
        }
        ScoreCalculator calculateScore =
                new ScoreCalculator(simConfig, options.trialsPerIndividual, morphology, options.hyperNEATM);

        if (!isBlank(options.genomePath))
        {
            NEATNetwork network = (NEATNetwork) readObjectFromFile(options.genomePath);
            calculateScore.demo(network);
            return;
        }


        final NEATPopulation population;
        if (!isBlank(options.populationPath))
        {
            population = (NEATPopulation) readObjectFromFile(options.populationPath);
        }
        else
        {
            if (options.hyperNEATM)
            {
                Substrate substrate = SubstrateFactory.createKheperaSubstrate(simConfig.getMinDistBetweenSensors(), simConfig.getRobotRadius());
                population = new NEATMPopulation(substrate, options.populationSize);
            }
            else
            {
                if (!options.control)
                {
                    population = new NEATMPopulation(2, options.populationSize);
                }
                else
                {
                    population = new NEATPopulation(morphology.getNumSensors(), 2, options.populationSize);
                }
            }
            population.setInitialConnectionDensity(options.connectionDensity);
            population.reset();

            log.debug("Population initialized");
        }

        TrainEA train;
        if(options.hyperNEATM)
        {
            train = org.encog.neural.neat.NEATUtil.constructNEATTrainer(population, calculateScore);
            train.setCODEC(new HyperNEATMCODEC());
        }
        else
        {
            if (!options.control)    // if using NEATM
            {
                train = NEATMUtil.constructNEATTrainer(population, calculateScore);
            } else                   // if using NEAT (control case)
            {
                train = NEATUtil.constructNEATTrainer(population, calculateScore);
            }
        }

        log.info("Available processors detected: " + Runtime.getRuntime().availableProcessors());
        if (options.threads > 0)
        {
            train.setThreadCount(options.threads);
        }

        final StatsRecorder statsRecorder = new StatsRecorder(train, calculateScore);

        for (int i = train.getIteration(); i < options.numGenerations; i++)
        {
            train.iteration();
            statsRecorder.recordIterationStats();

            if (train.getBestGenome().getScore() >= CONVERGENCE_SCORE)
            {
                log.info("Convergence reached at epoch " + train.getIteration());
                break;
            }
        }

        log.debug("Training complete");
        Encog.getInstance().shutdown();

        // #alex - save best network and run demo on it
        NEATNetwork bestPerformingNetwork = (NEATNetwork) train.getCODEC().decode(train.getBestGenome());   //extract best performing NN from the population
        calculateScore.demo(bestPerformingNetwork);
    }

    private static class Args
    {
        @Parameter(names = "-c", description = "Simulation config file to load")
        private String configFile = "config/bossConfig.yml";

        @Parameter(names = "-g", description = "Number of generations to train for")    // Jamie calls this 'iterations'
        private int numGenerations = 50;

        @Parameter(names = "-p", description = "Initial population size")
        private int populationSize = 75;

        @Parameter(names = "--trials", description = "Number of simulation runs per iteration (team lifetime)") // Jamie calls this 'simulationRuns' (and 'lifetime' in his paper)
        private int trialsPerIndividual = 3;

        @Parameter(names = "--conn-density", description = "Adjust the initial connection density"
                + " for the population")
        private double connectionDensity = 0.5;

        @Parameter(names = "--demo", description = "Show a GUI demo of a given genome")
        private String genomePath = null;

        @Parameter(names = "--control", description = "Run with the control case")
        private boolean control = false;

        @Parameter(names = "--morphology", description = "For use with the control case, provide"
                + " the path to a serialized MMNEATNetwork to have its morphology used for the"
                + " control case")
        private String morphologyPath = null;

        @Parameter(names = "--HyperNEATM", description = "Using HyperNEATM")
        private boolean hyperNEATM = true;

        @Parameter(names = "--population", description = "To resume a previous experiment, provide"
                + " the path to a serialized population")
        private String populationPath = null;

        @Parameter(names = "--threads", description = "Number of threads to run simulations with."
                + " By default Runtime#availableProcessors() is used to determine the number of threads to use")
        private int threads = 0;

        @Override
        public String toString()
        {
            return "Options: \n"
                    + "\tConfig file path: " + configFile + "\n"
                    + "\tNumber of simulation steps: " + numGenerations + "\n"
                    + "\tPopulation size: " + populationSize + "\n"
                    + "\tNumber of simulation tests per iteration: " + trialsPerIndividual + "\n"
                    + "\tInitial connection density: " + connectionDensity + "\n"
                    + "\tDemo network config path: " + genomePath + "\n"
                    + "\tRunning with the control case: " + control + "\n"
                    + "\tHyperNEATM: " + hyperNEATM + "\n"
                    + "\tMorphology path: " + morphologyPath + "\n"
                    + "\tPopulation path: " + populationPath + "\n"
                    + "\tNumber of threads: " + threads;
        }
    }
}
