package org.data2semantics.mustard.kernels.graphkernels.singledtgraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.data2semantics.mustard.kernels.ComputationTimeTracker;
import org.data2semantics.mustard.kernels.FeatureInspector;
import org.data2semantics.mustard.kernels.KernelUtils;
import org.data2semantics.mustard.kernels.SparseVector;
import org.data2semantics.mustard.kernels.data.SingleDTGraph;
import org.data2semantics.mustard.kernels.graphkernels.FeatureVectorKernel;
import org.data2semantics.mustard.kernels.graphkernels.GraphKernel;
import org.data2semantics.mustard.utils.WalkCountUtils;
import org.nodes.DTGraph;
import org.nodes.DTLink;
import org.nodes.DTNode;
import org.nodes.LightDTGraph;

/**
 * 
 * Root version (i.e. only walks that start in the root node are counted) of {@link org.data2semantics.mustard.kernels.graphkernels.singledtgraph.DTGraphWalkCountKernel}.
 * 
 * @author Gerben
 *
 */
public class DTGraphRootWalkCountKernel implements GraphKernel<SingleDTGraph>, FeatureVectorKernel<SingleDTGraph>, ComputationTimeTracker, FeatureInspector {

	private DTGraph<String,String> rdfGraph;
	private List<DTNode<String,String>> instanceVertices;

	private int pathLength;
	private boolean normalize;
	private long compTime;

	private Map<String, Integer> pathDict;
	private Map<String, Integer> labelDict;
	
	private Map<Integer, String> reversePathDict;
	private Map<Integer, String> reverseLabelDict;



	public DTGraphRootWalkCountKernel(int pathLength, boolean normalize) {
		this.normalize = normalize;
		this.pathLength = pathLength;
	}

	public String getLabel() {
		return KernelUtils.createLabel(this);		
	}

	public void setNormalize(boolean normalize) {
		this.normalize = normalize;
	}

	

	public long getComputationTime() {
		return compTime;
	}

	public SparseVector[] computeFeatureVectors(SingleDTGraph data) {
		pathDict  = new HashMap<String, Integer>();
		labelDict = new HashMap<String, Integer>();
		init(data.getGraph(), data.getInstances());

		// Initialize and compute the featureVectors
		SparseVector[] featureVectors = new SparseVector[data.numInstances()];
		for (int i = 0; i < featureVectors.length; i++) {
			featureVectors[i] = new SparseVector();
		}
		
		long tic = System.currentTimeMillis();
		for (int i = 0; i < featureVectors.length; i++) {
			countPathRec(featureVectors[i], instanceVertices.get(i), "", pathLength);
		}
		
		// Set the correct last index
		for (SparseVector fv : featureVectors) {
			fv.setLastIndex(pathDict.size()-1);
		}
		
		compTime = System.currentTimeMillis() - tic;
		
		reversePathDict = new HashMap<Integer,String>();	
		for (String key : pathDict.keySet()) {
			reversePathDict.put(pathDict.get(key), key);
		}
		
		reverseLabelDict = new HashMap<Integer,String>();	
		for (String key : labelDict.keySet()) {
			reverseLabelDict.put(labelDict.get(key), key);
		}

		if (this.normalize) {
			featureVectors = KernelUtils.normalize(featureVectors);
		}

		return featureVectors;
	}


	public double[][] compute(SingleDTGraph data) {
		SparseVector[] featureVectors = computeFeatureVectors(data);
		double[][] kernel = KernelUtils.initMatrix(data.getInstances().size(), data.getInstances().size());
		long tic = System.currentTimeMillis();
		kernel = KernelUtils.computeKernelMatrix(featureVectors, kernel);
		compTime += System.currentTimeMillis() - tic;
		return kernel;
	}

	private void countPathRec(SparseVector fv, DTNode<String,String> vertex, String path, int depth) {
		// Count path
		path = path + vertex.label();
		if (!pathDict.containsKey(path)) {
			pathDict.put(path, pathDict.size());
		}
		fv.setValue(pathDict.get(path), fv.getValue(pathDict.get(path)) + 1);
		if (depth > 0) {
			for (DTLink<String,String> edge : vertex.linksOut()) {
				countPathRec(fv, edge, path, depth-1);
			}
		}	
	}
	private void countPathRec(SparseVector fv, DTLink<String,String> edge, String path, int depth) {
		// Count path
		path = path + edge.tag();
		if (!pathDict.containsKey(path)) {
			pathDict.put(path, pathDict.size());
		}
		fv.setValue(pathDict.get(path), fv.getValue(pathDict.get(path)) + 1);
		if (depth > 0) {
			countPathRec(fv, edge.to(), path, depth-1);
		}	
	}

	private void init(DTGraph<String,String> graph, List<DTNode<String,String>> instances) {
		rdfGraph = new LightDTGraph<String,String>();
		instanceVertices = new ArrayList<DTNode<String,String>>();
		Map<DTNode<String,String>, Integer> instanceIndexMap = new HashMap<DTNode<String,String>, Integer>();

		for (int i = 0; i < instances.size(); i++) {
			instanceIndexMap.put(instances.get(i), i);
			instanceVertices.add(null);
		}

		for (DTNode<String,String> vertex : graph.nodes()) {
			if (!labelDict.containsKey(vertex.label())) {
				labelDict.put(vertex.label(), labelDict.size());
			}
			String lab = "_" + Integer.toString(labelDict.get(vertex.label()));

			if (instanceIndexMap.containsKey(vertex)) {
				instanceVertices.set(instanceIndexMap.get(vertex), rdfGraph.add(lab));
			} else {
				rdfGraph.add(lab);
			}


		}
		for (DTLink<String,String> edge : graph.links()) {
			if (!labelDict.containsKey(edge.tag())) {
				labelDict.put(edge.tag(), labelDict.size());
			}
			String lab = "_" + Integer.toString(labelDict.get(edge.tag()));

			rdfGraph.nodes().get(edge.from().index()).connect(rdfGraph.nodes().get(edge.to().index()), lab); // ?
		}	
	}

	public List<String> getFeatureDescriptions(List<Integer> indicesSV) {
		if (labelDict == null) {
			throw new RuntimeException("Should run computeFeatureVectors first");
		} else {
			List<String> desc = new ArrayList<String>();
			
			for (int index : indicesSV) {
				desc.add(WalkCountUtils.getFeatureDecription(reverseLabelDict, reversePathDict, index));
			}
			return desc;
		}
	}	
}
