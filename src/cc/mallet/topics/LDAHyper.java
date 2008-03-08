/* Copyright (C) 2005 Univ. of Massachusetts Amherst, Computer Science Dept.
   This file is part of "MALLET" (MAchine Learning for LanguagE Toolkit).
   http://www.cs.umass.edu/~mccallum/mallet
   This software is provided under the terms of the Common Public License,
   version 1.0, as published by http://www.opensource.org.	For further
   information, see the file `LICENSE' included with this distribution. */

package cc.mallet.topics;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

import java.util.zip.*;

import java.io.*;
import java.text.NumberFormat;

import cc.mallet.types.*;
import cc.mallet.util.Randoms;

/**
 * Latent Dirichlet Allocation with optimized hyperparameters
 * 
 * @author David Mimno, Andrew McCallum
 */

public class LDAHyper {

	int numTopics; // Number of topics to be fit
	int numTypes;

	double[] alpha;	 // Dirichlet(alpha,alpha,...) is the distribution over topics
	double alphaSum;

	double beta;   // Prior on per-topic multinomial distribution over words
	double betaSum;
	public static final double DEFAULT_BETA = 0.01;

	// Instance list containing word features
	InstanceList instances = null;

	// Instance list containing topic features
	InstanceList topics = null;

	// Instance list for empirical likelihood calculation
	InstanceList testing = null;
	
	// An array to put the topic counts for
	//  the current document. Defined here to avoid
	//   garbage collection overhead.
	int[] oneDocTopicCounts; // indexed by <document index, topic index>

	int[][] typeTopicCounts; // indexed by <feature index, topic index>
	int[] tokensPerTopic; // indexed by <topic index>

	double[] topicWeights;

	// for dirichlet estimation
	int[] docLengthCounts; // histogram of document sizes
	int[][] topicDocCounts; // histogram of document/topic counts

	int numIterations = 1000;
	int burninPeriod = 200;
	int saveSampleInterval = 10;	
	int optimizeInterval = 50;
	int showTopicsInterval = 50;
	int wordsPerTopic = 5;

	int outputModelInterval = 0;
	String outputModelFilename;

	int saveStateInterval = 0;
	String stateFilename = null;
	
	Randoms random;

	NumberFormat formatter;
	
	public LDAHyper (int numberOfTopics) {
		this (numberOfTopics, numberOfTopics, DEFAULT_BETA);
	}
	
	public LDAHyper (int numberOfTopics, double alphaSum, double beta)
	{
		formatter = NumberFormat.getInstance();
		formatter.setMaximumFractionDigits(5);
	
		System.out.println("LDA: " + numberOfTopics + " topics");
	
		this.numTopics = numberOfTopics;
		this.alphaSum = alphaSum;
		this.alpha = new double[numberOfTopics];

		Arrays.fill(alpha, alphaSum / numTopics);
		this.beta = beta;
	
		random = new Randoms();

		oneDocTopicCounts = new int[numTopics];
		tokensPerTopic = new int[numTopics];
		topicWeights = new double[numTopics];

	}
	
	/** Held-out instances for empirical likelihood calculation */
	public void setTestingInstances(InstanceList testing) {
		this.testing = testing;
	}

	public void setNumIterations (int numIterations) {
		this.numIterations = numIterations;
	}

	public void setBurninPeriod (int burninPeriod) {
		this.burninPeriod = burninPeriod;
	}

	public void setTopicDisplay(int interval, int n) {
		this.showTopicsInterval = interval;
		this.wordsPerTopic = n;
	}

	public void setRandomSeed(int seed) {
		random = new Randoms(seed);
	}

	public void setOptimizeInterval(int interval) {
		this.optimizeInterval = interval;
	}

	public void setModelOutput(int interval, String filename) {
		this.outputModelInterval = interval;
		this.outputModelFilename = filename;
	}
	
	/** Define how often and where to save the state 
	 *
	 * @param interval Save a copy of the state every <code>interval</code> iterations.
	 * @param filename Save the state to this file, with the iteration number as a suffix
	 */
	public void setSaveState(int interval, String filename) {
		this.saveStateInterval = interval;
		this.stateFilename = filename;
	}

	/** Set the instance list with word features for training */
	public void addInstances(InstanceList training) {
		this.instances = training;

		// We need to create a new instance list. 
		//  Since the topics aren't really meaningful on their own,
		// just add placeholders in the data alphabet.

		Alphabet topicAlphabet = new Alphabet();

		System.out.println("adding alphabet " + topicAlphabet);

		for (int topic=0; topic < numTopics; topic++) {
			topicAlphabet.lookupIndex("Topic " + topic, true);
		}

		this.topics = new InstanceList(topicAlphabet, null);

		numTypes = instances.getDataAlphabet().size ();	   
		typeTopicCounts = new int[numTypes][numTopics];
		betaSum = beta * numTypes;

		List<Instance> rawTopicInstances = new ArrayList<Instance>();
		
		for (Instance instance: instances) {
			FeatureSequence tokenSequence = (FeatureSequence) instance.getData();
			rawTopicInstances.add(new Instance( new FeatureSequence( topicAlphabet,
																	 new int[tokenSequence.getLength()] ),
												null, null, null ));
		}			
			
		// Run the instances through the pipe
		
		topics.addThruPipe(rawTopicInstances.iterator());

		for (int doc=0; doc < instances.size(); doc++) {
			FeatureSequence tokenSequence = (FeatureSequence) instances.get(doc).getData();
			FeatureSequence topicSequence = (FeatureSequence) topics.get(doc).getData();

			// Sample topics according to the predictive distribution			
			sampleTopicsForOneDoc(tokenSequence, topicSequence,
								  false, true);	//   (don't store histograms, do start from nothing)
		}

		initializeHistograms();
	}

	public void addInstances(InstanceList training, InstanceList topics) {
		this.instances = training;		
		this.topics = topics;

		numTypes = instances.getDataAlphabet().size ();
		typeTopicCounts = new int[numTypes][numTopics];
		betaSum = beta * numTypes;

		for (int doc = 0; doc < instances.size(); doc++) {
			
			FeatureSequence tokenSequence = (FeatureSequence) instances.get(doc).getData();
			FeatureSequence topicSequence = (FeatureSequence) topics.get(doc).getData();
			
			for (int token = 0; token < topicSequence.getLength(); token++) {
				int topic = topicSequence.getIndexAtPosition(token);
				typeTopicCounts[ tokenSequence.getIndexAtPosition(token) ][topic]++;
				tokensPerTopic[topic]++;
			}
		}

		initializeHistograms();
	}

	/** 
	 *  Gather statistics on the size of documents 
	 *  and create histograms for use in Dirichlet hyperparameter
	 *  optimization.
	 */
	private void initializeHistograms() {

		int maxTokens = 0;
		int totalTokens = 0;
		int topic, seqLen;

		for (int doc = 0; doc < instances.size(); doc++) {
			FeatureSequence fs = (FeatureSequence) instances.get(doc).getData();
			seqLen = fs.getLength();
			if (seqLen > maxTokens) { 
				maxTokens = seqLen;
			}
			totalTokens += seqLen;
		}

		System.out.println("max tokens: " + maxTokens);
		System.out.println("total tokens: " + totalTokens);

		docLengthCounts = new int[maxTokens + 1];
		topicDocCounts = new int[numTopics][maxTokens + 1];
	}
	
	public void estimate() throws IOException {

		long startTime = System.currentTimeMillis();
	
		for (int iterations = 1; iterations <= numIterations; iterations++) {
			long iterationStart = System.currentTimeMillis();

			if (showTopicsInterval != 0 && iterations % showTopicsInterval == 0) {
				System.out.println();
				System.out.println(printTopWords (wordsPerTopic, false));

				/*
				  if (testing != null) {
				  double el = empiricalLikelihood(1000, testing);
				  }
				  double ll = modelLogLikelihood();
				  double mi = topicLabelMutualInformation();
				  System.out.println(ll + "\t" + el + "\t" + mi);
				*/
			}

			if (saveStateInterval != 0 && iterations % saveStateInterval == 0) {
				this.printState(new File(stateFilename + '.' + iterations));
			}

			/*
			  if (outputModelInterval != 0 && iterations % outputModelInterval == 0) {
			  this.write (new File(outputModelFilename+'.'+iterations));
			  }
			*/
			if (iterations > burninPeriod && 
				optimizeInterval != 0 &&
				iterations % optimizeInterval == 0) {

				long optimizeTime = System.currentTimeMillis();
				alphaSum = Dirichlet.learnParameters(alpha, topicDocCounts, docLengthCounts);
				//System.out.print("[o:" + (System.currentTimeMillis() - optimizeTime) + "]");
				clearHistograms();
			}

			// Loop over every document in the corpus
			for (int doc = 0; doc < topics.size(); doc++) {

				FeatureSequence tokenSequence = (FeatureSequence) instances.get(doc).getData();
				FeatureSequence topicSequence = (FeatureSequence) topics.get(doc).getData();

				sampleTopicsForOneDoc (tokenSequence, topicSequence,
									   iterations > burninPeriod &&
									   iterations % saveSampleInterval == 0,
									   false);
			}
		
			System.out.print((System.currentTimeMillis() - iterationStart) + " ");
			if (iterations % 10 == 0) {
				System.out.println ("<" + iterations + "> ");
				System.out.println (modelLogLikelihood());
			}
			System.out.flush();
		}
	
		long seconds = Math.round((System.currentTimeMillis() - startTime)/1000.0);
		long minutes = seconds / 60;	seconds %= 60;
		long hours = minutes / 60;	minutes %= 60;
		long days = hours / 24;	hours %= 24;
		System.out.print ("\nTotal time: ");
		if (days != 0) { System.out.print(days); System.out.print(" days "); }
		if (hours != 0) { System.out.print(hours); System.out.print(" hours "); }
		if (minutes != 0) { System.out.print(minutes); System.out.print(" minutes "); }
		System.out.print(seconds); System.out.println(" seconds");
	
	}
	
	private void clearHistograms() {
		Arrays.fill(docLengthCounts, 0);
		for (int topic = 0; topic < topicDocCounts.length; topic++) {
			Arrays.fill(topicDocCounts[topic], 0);
		}
	}

	private void sampleTopicsForOneDoc (FeatureSequence tokenSequence, 
										FeatureSequence topicSequence,
										boolean shouldSaveState, boolean initializing) {

		long startTime = System.currentTimeMillis();
	
		int[] oneDocTopics = topicSequence.getFeatures();

		int[] currentTypeTopicCounts;
		int type, oldTopic, newTopic;
		double topicWeightsSum;
		int docLen = tokenSequence.getLength();

		double weight;
	
		// populate topic counts
		Arrays.fill(oneDocTopicCounts, 0);

		if (! initializing) {
			for (int token = 0; token < docLen; token++) {
				oneDocTopicCounts[ oneDocTopics[token] ]++;
			}
		}

		// Iterate over the potokentions (words) in the document
		for (int token = 0; token < docLen; token++) {
			type = tokenSequence.getIndexAtPosition(token);
			oldTopic = oneDocTopics[token];

			if (! initializing) {
				// Remove this token from all counts
				oneDocTopicCounts[oldTopic]--;
				typeTopicCounts[type][oldTopic]--;
				tokensPerTopic[oldTopic]--;
			}

			// Build a distribution over topics for this token
			topicWeightsSum = 0;
			currentTypeTopicCounts = typeTopicCounts[type];
			for (int topic = 0; topic < numTopics; topic++) {
				weight = ((currentTypeTopicCounts[topic] + beta) /
						  (tokensPerTopic[topic] + betaSum))
					* ((oneDocTopicCounts[topic] + alpha[topic]));
				topicWeightsSum += weight;
				topicWeights[topic] = weight;
			}

			// Sample a topic assignment from this distribution
			newTopic = random.nextDiscrete (topicWeights, topicWeightsSum);
		
			// Put that new topic into the counts
			oneDocTopics[token] = newTopic;
			oneDocTopicCounts[newTopic]++;
			typeTopicCounts[type][newTopic]++;
			tokensPerTopic[newTopic]++;
		}

		if (shouldSaveState) {

			// Update the document-topic count histogram,
			//	for dirichlet estimation
			docLengthCounts[ docLen ]++;
			for (int topic=0; topic < numTopics; topic++) {
				topicDocCounts[topic][ oneDocTopicCounts[topic] ]++;
			}
		}
	}

	public IDSorter[] getSortedTopicWords(int topic) {
		IDSorter[] sortedTypes = new IDSorter[ numTypes ];

		for (int type = 0; type < numTypes; type++) {
			sortedTypes[type] = new IDSorter(type, typeTopicCounts[type][topic]);
		}

		Arrays.sort(sortedTypes);
		return sortedTypes;
	}

	public void writeTopicWords (int numWords, boolean useNewLines, File file) throws IOException {
		PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(file)));
		out.println(printTopWords(numWords, useNewLines));
		out.close();
	}
	
	public String printTopWords (int numWords, boolean usingNewLines) {

		StringBuffer output = new StringBuffer();
		Alphabet alphabet = instances.getDataAlphabet();

		for (int topic = 0; topic < numTopics; topic++) {

			IDSorter[] sortedTypes = getSortedTopicWords(topic);

			if (usingNewLines) {
				output.append ("Topic " + topic + "\n");

				for (int i = 0; i < numWords && i < sortedTypes.length; i++) {
					output.append (alphabet.lookupObject(sortedTypes[i].getID()) + "\t" +
								   formatter.format(sortedTypes[i].getWeight()) + "\n");
				}

				output.append("\n");
			}
			else {
				output.append (topic + "\t" + 
							   formatter.format(alpha[topic]) + "\t");

				for (int i = 0; i < numWords; i++) {
					output.append (alphabet.lookupObject(sortedTypes[i].getID()) + " ");
				}

				output.append("\n");
			}
		}

		return output.toString();
	}

	public void printDocumentTopics (File f) throws IOException {
		printDocumentTopics (new PrintWriter (new FileWriter (f) ) );
	}

	public void printDocumentTopics (PrintWriter pw) {
		printDocumentTopics (pw, 0.0, -1);
	}

	/**
	 *  @param pw          A print writer
	 *  @param threshold   Only print topics with proportion greater than this number
	 *  @param max         Print no more than this many topics
	 */
	public void printDocumentTopics (PrintWriter pw, double threshold, int max)	{
		StringBuffer output = new StringBuffer();

		output.append ("#doc source topic proportion ...\n");
		int docLen;
		int[] topicCounts = new int[ numTopics ];

		IDSorter[] sortedTopics = new IDSorter[ numTopics ];
		for (int topic = 0; topic < numTopics; topic++) {
			// Initialize the sorters with dummy values
			sortedTopics[topic] = new IDSorter(topic, topic);
		}

		if (max < 0 || max > numTopics) {
			max = numTopics;
		}

		for (int doc = 0; doc < topics.size(); doc++) {
			FeatureSequence topicSequence = 
				(FeatureSequence) topics.get(doc).getData();
			int[] currentDocTopics = topicSequence.getFeatures();

			output.append (doc); output.append (' ');

			if (instances.get(doc).getSource() != null) {
				output.append (instances.get(doc).getSource()); 
			}
			else {
				output.append("null-source");
			}

			output.append (' ');
			docLen = currentDocTopics.length;

			// Count up the tokens
			for (int token=0; token < docLen; token++) {
				topicCounts[ currentDocTopics[token] ]++;
			}

			// And normalize
			for (int topic = 0; topic < numTopics; topic++) {
				sortedTopics[topic].set(topic, (float) topicCounts[topic] / docLen);
			}
			
			Arrays.sort(sortedTopics);

			for (int i = 0; i < max; i++) {
				if (sortedTopics[i].getWeight() < threshold) { break; }
				
				output.append (sortedTopics[i].getID() + " " + 
							   sortedTopics[i].getWeight() + " ");
			}
			output.append (" \n");

			pw.print(output.toString());
			output = new StringBuffer();
		}
		
	}
	
	public void printState (File f) throws IOException {
		PrintStream out =
			new PrintStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(f))));
		printState(out);
		out.close();
	}
	
	public void printState (PrintStream out) {
		Alphabet alphabet = instances.getDataAlphabet();

		out.println ("#doc pos typeindex type topic");
		for (int doc = 0; doc < topics.size(); doc++) {

			FeatureSequence tokenSequence =
				(FeatureSequence) instances.get(doc).getData();
			FeatureSequence topicSequence =
				(FeatureSequence) instances.get(doc).getData();

			for (int token = 0; token < topicSequence.getLength(); token++) {
				int type = tokenSequence.getIndexAtPosition(token);
				int topic = topicSequence.getIndexAtPosition(token);
				out.print(doc); out.print(' ');
				out.print(token); out.print(' ');
				out.print(type); out.print(' ');
				out.print(alphabet.lookupObject(type)); out.print(' ');
				out.print(topic); out.println();
			}
		}
	}
	
	public void write (File f) {
		try {
			ObjectOutputStream oos = new ObjectOutputStream (new FileOutputStream(f));
			oos.writeObject(this);
			oos.close();
		}
		catch (IOException e) {
			System.err.println("Exception writing file " + f + ": " + e);
		}
	}
	
	
	// Serialization
	
	private static final long serialVersionUID = 1;
	private static final int CURRENT_SERIAL_VERSION = 0;
	private static final int NULL_INTEGER = -1;
	
	private void writeObject (ObjectOutputStream out) throws IOException {
		out.writeInt (CURRENT_SERIAL_VERSION);

		// Instance lists
		out.writeObject (instances);
		out.writeObject (topics);

		out.writeInt (numTopics);
		out.writeObject (alpha);
		out.writeDouble (beta);
		out.writeDouble (betaSum);

		for (int fi = 0; fi < numTypes; fi++)
			for (int ti = 0; ti < numTopics; ti++)
				out.writeInt (typeTopicCounts[fi][ti]);

		for (int ti = 0; ti < numTopics; ti++)
			out.writeInt (tokensPerTopic[ti]);
	}
	
	private void readObject (ObjectInputStream in) throws IOException, ClassNotFoundException {
		int featuresLength;
		int version = in.readInt ();

		instances = (InstanceList) in.readObject ();
		topics = (InstanceList) in.readObject ();

		numTopics = in.readInt();
		alpha = (double[]) in.readObject();
		beta = in.readDouble();
		betaSum = in.readDouble();
		int numDocs = instances.size();

		int numTypes = instances.getDataAlphabet().size();
		typeTopicCounts = new int[numTypes][numTopics];
		for (int fi = 0; fi < numTypes; fi++)
			for (int ti = 0; ti < numTopics; ti++)
				typeTopicCounts[fi][ti] = in.readInt();
		tokensPerTopic = new int[numTopics];
		for (int ti = 0; ti < numTopics; ti++)
			tokensPerTopic[ti] = in.readInt();
	}


	public double topicLabelMutualInformation() {
		int doc, level, label, topic, token, type;
		int[] docTopics;

		int[][] topicLabelCounts = new int[ numTopics ][ instances.getTargetAlphabet().size() ];
		int[] topicCounts = new int[ numTopics ];
		int[] labelCounts = new int[ instances.getTargetAlphabet().size() ];
		int total = 0;

		for (doc=0; doc < instances.size(); doc++) {
			label = instances.get(doc).getLabeling().getBestIndex();

			FeatureSequence topicSequence = 
				(FeatureSequence) topics.get(doc).getData();
			docTopics = topicSequence.getFeatures();

			for (token = 0; token < docTopics.length; token++) {
				topic = docTopics[token];
				topicLabelCounts[ topic ][ label ]++;
				topicCounts[topic]++;
				labelCounts[label]++;
				total++;
			}
		}

		/* // This block will print out the best topics for each label

		IDSorter[] wp = new IDSorter[numTypes];

		for (topic = 0; topic < numTopics; topic++) {

		for (type = 0; type < numTypes; type++) {
		wp[type] = new IDSorter (type, (((double) typeTopicCounts[type][topic]) /
		tokensPerTopic[topic]));
		}
		Arrays.sort (wp);

		StringBuffer terms = new StringBuffer();
		for (int i = 0; i < 8; i++) {
		terms.append(instances.getDataAlphabet().lookupObject(wp[i].id));
		terms.append(" ");
		}

		System.out.println(terms);
		for (label = 0; label < topicLabelCounts[topic].length; label++) {
		System.out.println(topicLabelCounts[ topic ][ label ] + "\t" +
		instances.getTargetAlphabet().lookupObject(label));
		}
		System.out.println();
		}

		*/

		double topicEntropy = 0.0;
		double labelEntropy = 0.0;
		double jointEntropy = 0.0;
		double p;
		double log2 = Math.log(2);

		for (topic = 0; topic < topicCounts.length; topic++) {
			if (topicCounts[topic] == 0) { continue; }
			p = (double) topicCounts[topic] / total;
			topicEntropy -= p * Math.log(p) / log2;
		}

		for (label = 0; label < labelCounts.length; label++) {
			if (labelCounts[label] == 0) { continue; }
			p = (double) labelCounts[label] / total;
			labelEntropy -= p * Math.log(p) / log2;
		}

		for (topic = 0; topic < topicCounts.length; topic++) {
			for (label = 0; label < labelCounts.length; label++) {
				if (topicLabelCounts[ topic ][ label ] == 0) { continue; }
				p = (double) topicLabelCounts[ topic ][ label ] / total;
				jointEntropy -= p * Math.log(p) / log2;
			}
		}

		return topicEntropy + labelEntropy - jointEntropy;


	}

	public double empiricalLikelihood(int numSamples, InstanceList testing) {
		double[][] likelihoods = new double[ testing.size() ][ numSamples ];
		double[] multinomial = new double[numTypes];
		double[] topicDistribution, currentSample, currentWeights;
		Dirichlet topicPrior = new Dirichlet(alpha);	   

		int sample, doc, topic, type, token, seqLen;
		FeatureSequence fs;

		for (sample = 0; sample < numSamples; sample++) {
			topicDistribution = topicPrior.nextDistribution();
			Arrays.fill(multinomial, 0.0);

			for (topic = 0; topic < numTopics; topic++) {
				for (type=0; type<numTypes; type++) {
					multinomial[type] += 
						topicDistribution[topic] *
						(beta + typeTopicCounts[type][topic]) /
						(betaSum + tokensPerTopic[topic]);
				}
			}

			// Convert to log probabilities
			for (type=0; type<numTypes; type++) {
				assert(multinomial[type] > 0.0);
				multinomial[type] = Math.log(multinomial[type]);
			}

			for (doc=0; doc<testing.size(); doc++) {
				fs = (FeatureSequence) testing.get(doc).getData();
				seqLen = fs.getLength();

				for (token = 0; token < seqLen; token++) {
					type = fs.getIndexAtPosition(token);
					likelihoods[doc][sample] += multinomial[type];
				}
			}
		}

		double averageLogLikelihood = 0.0;
		double logNumSamples = Math.log(numSamples);
		for (doc=0; doc<testing.size(); doc++) {
			double max = Double.NEGATIVE_INFINITY;
			for (sample = 0; sample < numSamples; sample++) {
				if (likelihoods[doc][sample] > max) {
					max = likelihoods[doc][sample];
				}
			}

			double sum = 0.0;
			for (sample = 0; sample < numSamples; sample++) {
				sum += Math.exp(likelihoods[doc][sample] - max);
			}

			averageLogLikelihood += Math.log(sum) + max - logNumSamples;
		}

		return averageLogLikelihood;

	}

	public double modelLogLikelihood() {
		double logLikelihood = 0.0;
		int nonZeroTopics;

		// The likelihood of the model is a combination of a 
		// Dirichlet-multinomial for the words in each topic
		// and a Dirichlet-multinomial for the topics in each
		// document.

		// The likelihood function of a dirichlet multinomial is
		//	 Gamma( sum_i alpha_i )	 prod_i Gamma( alpha_i + N_i )
		//	prod_i Gamma( alpha_i )	  Gamma( sum_i (alpha_i + N_i) )

		// So the log likelihood is 
		//	logGamma ( sum_i alpha_i ) - logGamma ( sum_i (alpha_i + N_i) ) + 
		//	 sum_i [ logGamma( alpha_i + N_i) - logGamma( alpha_i ) ]

		// Do the documents first

		int[] topicCounts = new int[numTopics];
		double[] topicLogGammas = new double[numTopics];
		int[] docTopics;

		for (int topic=0; topic < numTopics; topic++) {
			topicLogGammas[ topic ] = Dirichlet.logGammaStirling( alpha[topic] );
		}
	
		for (int doc=0; doc < topics.size(); doc++) {
			FeatureSequence topicSequence = 
				(FeatureSequence) topics.get(doc).getData();

			docTopics = topicSequence.getFeatures();

			for (int token=0; token < docTopics.length; token++) {
				topicCounts[ docTopics[token] ]++;
			}

			for (int topic=0; topic < numTopics; topic++) {
				if (topicCounts[topic] > 0) {
					logLikelihood += (Dirichlet.logGammaStirling(alpha[topic] + topicCounts[topic]) -
									  topicLogGammas[ topic ]);
				}
			}

			// subtract the (count + parameter) sum term
			logLikelihood -= Dirichlet.logGammaStirling(alphaSum + docTopics.length);

			Arrays.fill(topicCounts, 0);
		}
	
		// add the parameter sum term
		logLikelihood += topics.size() * Dirichlet.logGammaStirling(alphaSum);

		// And the topics

		// Count the number of type-topic pairs
		int nonZeroTypeTopics = 0;

		for (int type=0; type < numTypes; type++) {
			// reuse this array as a pointer
			topicCounts = typeTopicCounts[type];

			for (int topic=0; topic < numTopics; topic++) {
				if (topicCounts[topic] > 0) {
					nonZeroTypeTopics++;
					logLikelihood += Dirichlet.logGammaStirling(beta + topicCounts[topic]);
				}
			}
		}
	
		for (int topic=0; topic < numTopics; topic++) {
			logLikelihood -= 
				Dirichlet.logGammaStirling( (beta * numTopics) +
											tokensPerTopic[ topic ] );
		}
	
		logLikelihood += 
			(Dirichlet.logGammaStirling(beta * numTopics)) -
			(Dirichlet.logGammaStirling(beta) * nonZeroTypeTopics);
	
		return logLikelihood;
	}
	
	// Recommended to use mallet/bin/vectors2topics instead.
	public static void main (String[] args) throws IOException {

		InstanceList training = InstanceList.load (new File(args[0]));

		int numTopics = args.length > 1 ? Integer.parseInt(args[1]) : 200;

		InstanceList testing = 
			args.length > 2 ? InstanceList.load (new File(args[2])) : null;

		LDAHyper lda = new LDAHyper (numTopics, 50.0, 0.01);
		lda.addInstances(training);
		lda.estimate();
	}
	
}
