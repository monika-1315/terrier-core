/*
 * Terrier - Terabyte Retriever 
 * Webpage: http://terrier.org 
 * Contact: terrier{a.}dcs.gla.ac.uk
 * University of Glasgow - School of Computing Science
 * http://www.gla.ac.uk/
 * 
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is JforestsModelMatching.java.
 *
 * The Original Code is Copyright (C) 2004-2016 the University of Glasgow.
 * All Rights Reserved.
 *
 * Contributor(s):
 *   Craig Macdonald <craigm{a.}dcs.gla.ac.uk>
 */

package org.terrier.matching;

import java.io.File;
import java.util.Arrays;

import org.terrier.learning.FeaturedResultSet;
import org.terrier.structures.Index;
import org.terrier.utility.ApplicationSetup;

import edu.uci.jforests.dataset.BitNumericArray;
import edu.uci.jforests.dataset.ByteNumericArray;
import edu.uci.jforests.dataset.Feature;
import edu.uci.jforests.dataset.NullNumericArray;
import edu.uci.jforests.dataset.NumericArray;
import edu.uci.jforests.dataset.RankingDataset;
import edu.uci.jforests.dataset.ShortNumericArray;
import edu.uci.jforests.input.FeatureAnalyzer;
import edu.uci.jforests.learning.LearningUtils;
import edu.uci.jforests.learning.trees.Ensemble;
import edu.uci.jforests.learning.trees.Tree;
import edu.uci.jforests.learning.trees.decision.DecisionTree;
import edu.uci.jforests.learning.trees.regression.RegressionTree;
import edu.uci.jforests.sample.RankingSample;
import edu.uci.jforests.sample.Sample;
import gnu.trove.TIntIntHashMap;

/** Applies a Jforests regression tree learned model to a {@link FeaturedResultSet}. Learned model files are generated by Jforests.
 * If you use this class, you are implicitly using the jforests library, and the <a href="https://code.google.com/p/jforests/"
 * Jforests citation policy</a> applies.
 * <p><b>Properties</b></p>
 * <ul>
 * <li><tt>fat.matching.learned.jforest.model</tt> - filename of the ensemble model generated by jforests</li>
 * <li><tt>fat.matching.learned.jforest.statistics</tt> - filename of the feature statistics file generated by jforests</li>
 * </ul>
 * @author Craig Macdonald
 * @since 4.0
 */
public class JforestsModelMatching extends LearnedModelMatching {

	final Ensemble ensemble = new Ensemble();
	final FeatureAnalyzer featureAnalyzer = new FeatureAnalyzer();
	
	public JforestsModelMatching(Index _index, Matching _parent) throws Exception {
		super(_index, _parent);
		loadModel(ApplicationSetup.getProperty("fat.matching.learned.jforest.model", null));
	}

	public JforestsModelMatching(Matching _parent) throws Exception {
		super(_parent);
		loadModel(ApplicationSetup.getProperty("fat.matching.learned.jforest.model", null));
	}
	
	public JforestsModelMatching(Matching _parent, String modelFilename) throws Exception {
		super(_parent);
		loadModel(modelFilename);
	}
	
	public JforestsModelMatching(Index _index, Matching _parent, String modelFilename) throws Exception {
		super(_parent);
		loadModel(modelFilename);
	}
	
	public JforestsModelMatching(Index _index, Matching _parent, String modelFilename, Class<? extends Tree> treeClass) throws Exception {
		super(_parent);
		loadModel(modelFilename, treeClass);
	}
	
	protected void loadModel(String model_filename) throws Exception
	{
		final boolean regression = true;
		loadModel(model_filename, regression ? RegressionTree.class : DecisionTree.class);
	}
	
	protected void loadModel(String model_filename, Class<? extends Tree> treeClass) throws Exception
	{
		ensemble.loadFromFile(treeClass, new File(model_filename));
		final String featureStats_filename = 
				ApplicationSetup.getProperty("fat.matching.learned.jforest.statistics", model_filename+".features");
		featureAnalyzer.loadFeaturesFromFile(featureStats_filename);
	}
	
	protected RankingDataset makeDataset(int N, int featureCount, double[][] doubleFeatures)
	{
		//doubleFeatures: indexed by feature then document
		//intFeatures: indexed by document then feature
		final int[][] intFeatures = new int[N][featureCount];
        TIntIntHashMap[] _valueHashMap = new TIntIntHashMap[featureCount];
        for (int f = 0; f < featureCount; f++) {
                _valueHashMap[f] = new TIntIntHashMap();
        }
        
        //we are also rotating here.
        for(int d=0;d<N;d++)
        {
        	for(int f=0;f<featureCount;f++)
        	{
        		double value = doubleFeatures[f][d];
        		if (featureAnalyzer.onLogScale[f])
        			value = (Math.log(value - featureAnalyzer.min[f] + 1) * featureAnalyzer.factor[f]);
        		else
        			value = (value - featureAnalyzer.min[f]) * featureAnalyzer.factor[f];
        		int intValue = intFeatures[d][f] = (int)Math.round(value);
        		_valueHashMap[f].adjustOrPutValue(intValue, 1, 1);
        	}
        }
        final int[][] valueDistributions = new int[featureCount][];
                TIntIntHashMap _curMap;
        for (int f = 0; f < featureCount; f++) {
                _curMap = _valueHashMap[f];
                if (! _curMap.containsKey(0))
                	_curMap.put(0,0);
                valueDistributions[f] = _curMap.keys();
                Arrays.sort(valueDistributions[f]);
        }

        final NumericArray[] bins = new NumericArray[featureCount];        
        for (int i = 0; i < featureCount; i++) {
            int numValues = valueDistributions[i].length;
            if (numValues == 1 && valueDistributions[i][0] == 0) {
                    bins[i] = NullNumericArray.getInstance();
            } else if (numValues <= 2) {
                    bins[i] = new BitNumericArray(N);
            } else if (numValues <= Byte.MAX_VALUE) {
                    bins[i] = new ByteNumericArray(N);
            } else if (numValues <= Short.MAX_VALUE) {
                    bins[i] = new ShortNumericArray(N);
            } else {
                    throw new RuntimeException("One of your features have more than " + Short.MAX_VALUE
                                    + " distinct values. The support for this feature is not implemented yet.");
            }
        }
        

        final double[] targets = new double[N];
        for(int d=0;d<N;d++)
        {
        	for(int f=0;f<featureCount;f++)
        	{
        		int index = Arrays.binarySearch(valueDistributions[f], intFeatures[d][f]);
        		bins[f].set(d, index);
        	}
        }
        Feature[] features = new Feature[featureCount];
        for (int f = 0; f < featureCount; f++) {
                features[f] = new Feature(bins[f]);
                features[f].upperBounds = valueDistributions[f];
                features[f].setName(String.valueOf(f+1));
                features[f].setMin(featureAnalyzer.min[f]);
                features[f].setMax(featureAnalyzer.max[f]);
                features[f].setFactor(featureAnalyzer.factor[f]);
                features[f].setOnLogScale(featureAnalyzer.onLogScale[f]);
        }
        RankingDataset rtr = new RankingDataset();
        rtr.init(features, targets, new int[]{0}, N);
        return rtr;
	}
	
	@Override
	protected void applyModel(int N, double[] in_scores, int featureCount,
			double[][] doubleFeatures, double[] out_scores) 
	{	
		//doubleFeatures is indexed by feature then document
		if (score_is_feature)
		{
			final double[][] doubleFeaturesNew = new double[featureCount+1][];
			doubleFeaturesNew[0] = in_scores;
			for(int j=0;j<featureCount;j++)
				doubleFeaturesNew[j+1] = doubleFeatures[j];
			doubleFeatures = doubleFeaturesNew;
			featureCount++;
		}
		
		RankingDataset dataset = makeDataset(N, featureCount, doubleFeatures);
		Sample sample = new RankingSample(dataset);
		assert sample.size == out_scores.length;
        LearningUtils.updateScores(sample, out_scores, ensemble);
	}
	

	static double[][] rotate(final double[][] in)
	{
		final int I= in.length;
		final int J = in[0].length;
		final double[][] out = new double[J][I];		
		for(int i=0;i<I;i++)
			for(int j=0;j<J;j++)
				out[j][i] = in[i][j];
		return out;
	}

}
