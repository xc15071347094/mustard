package org.data2semantics.mustard.kernels.graphkernels.singledtgraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.data2semantics.mustard.kernels.ComputationTimeTracker;
import org.data2semantics.mustard.kernels.KernelUtils;
import org.data2semantics.mustard.kernels.SparseVector;
import org.data2semantics.mustard.kernels.data.SingleDTGraph;
import org.data2semantics.mustard.kernels.graphkernels.FeatureVectorKernel;
import org.data2semantics.mustard.kernels.graphkernels.GraphKernel;

import org.data2semantics.mustard.utils.Pair;
import org.nodes.DTGraph;
import org.nodes.DTLink;
import org.nodes.DTNode;
import org.nodes.LightDTGraph;

/**
 * 
 * @author Gerben
 *
 */
public class DTGraphTreeWalkCountIDEQApproxKernelMkII implements GraphKernel<SingleDTGraph>, FeatureVectorKernel<SingleDTGraph>, ComputationTimeTracker {

	private Map<DTNode<PathStringLabel,PathStringLabel>, List<Pair<DTNode<PathStringLabel,PathStringLabel>, Integer>>> instanceVertexIndexMap;
	private Map<DTNode<PathStringLabel,PathStringLabel>, List<Pair<DTLink<PathStringLabel,PathStringLabel>, Integer>>> instanceEdgeIndexMap;

	private DTGraph<PathStringLabel,PathStringLabel> rdfGraph;
	private List<DTNode<PathStringLabel,PathStringLabel>> instanceVertices;

	private int depth;
	private int pathLength;
	private boolean normalize;
	private int minFreq;
	private long compTime;

	private Map<String, Integer> pathDict;
	private Map<String, Integer> labelDict;

	private Map<String,Integer> labelFreq;
	private Map<String,Integer> pathFreq;


	public DTGraphTreeWalkCountIDEQApproxKernelMkII(int pathLength, int depth, int minFreq, boolean normalize) {
		this.normalize = normalize;
		this.depth = depth;
		this.pathLength = pathLength;
		this.minFreq = minFreq;
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
		instanceVertices = new ArrayList<DTNode<PathStringLabel,PathStringLabel>>();
		this.instanceVertexIndexMap = new HashMap<DTNode<PathStringLabel,PathStringLabel>, List<Pair<DTNode<PathStringLabel,PathStringLabel>, Integer>>>();
		this.instanceEdgeIndexMap = new HashMap<DTNode<PathStringLabel,PathStringLabel>, List<Pair<DTLink<PathStringLabel,PathStringLabel>, Integer>>>();

		pathDict  = new HashMap<String, Integer>();
		labelDict = new HashMap<String, Integer>();
		init(data.getGraph(), data.getInstances());

		// Initialize and compute the featureVectors
		SparseVector[] featureVectors = new SparseVector[data.numInstances()];
		for (int i = 0; i < featureVectors.length; i++) {
			featureVectors[i] = new SparseVector();
		}

		long tic = System.currentTimeMillis();

		// initial count
		// Count paths
		Integer index = null;
		for (DTNode<PathStringLabel,PathStringLabel> v : rdfGraph.nodes()) {
			for (String path : v.label().getPaths()) {
				index = pathDict.get(path);
				if (index == null) {
					index = pathDict.size();
					pathDict.put(path, index);
				}
			}
		}

		for (DTLink<PathStringLabel,PathStringLabel> e : rdfGraph.links()) {	
			for (String path : e.tag().getPaths()) {
				index = pathDict.get(path);
				if (index == null) {
					index = pathDict.size();
					pathDict.put(path, index);
				}
			}
		}

		computeFVs(rdfGraph, instanceVertices, featureVectors, pathDict.size()-1, 0);

		computeLabelFreqs(rdfGraph, instanceVertices);

		// loop to create longer and longer paths
		for (int j = 0; j < pathLength; j++) {

			computePathFreqs(rdfGraph, instanceVertices, j);

			// Build new paths
			for (DTNode<PathStringLabel,PathStringLabel> v : rdfGraph.nodes()) {
				if (v.label().getMaxDepth() * 2 >= j+1) { // only add it if we are interested for some instances
					for (DTLink<PathStringLabel,PathStringLabel> e : v.linksOut()) {
						v.label().addPaths(e.tag().getPaths());
					}
				}
			}
			for (DTLink<PathStringLabel,PathStringLabel> e : rdfGraph.links()) {	
				if ((e.tag().getMaxDepth() * 2) >= j) {
					e.tag().addPaths(e.to().label().getPaths());
				}
			}

			// Count paths
			for (DTNode<PathStringLabel,PathStringLabel> v : rdfGraph.nodes()) {
				v.label().setNewPaths();
				for (String path : v.label().getPaths()) {
					index = pathDict.get(path);
					if (index == null) {
						index = pathDict.size();
						pathDict.put(path, index);
					}
				}
			}
			for (DTLink<PathStringLabel,PathStringLabel> e : rdfGraph.links()) {	
				e.tag().setNewPaths();
				for (String path : e.tag().getPaths()) {
					index = pathDict.get(path);
					if (index == null) {
						index = pathDict.size();
						pathDict.put(path, index);
					}
				}
			}		
			computeFVs(rdfGraph, instanceVertices, featureVectors, pathDict.size()-1, j+1);
		}

		compTime = System.currentTimeMillis() - tic;

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



	private void init(DTGraph<String,String> graph, List<DTNode<String,String>> instances) {
		DTNode<PathStringLabel,PathStringLabel> startV;
		List<DTNode<String,String>> frontV, newFrontV;
		List<Pair<DTNode<PathStringLabel,PathStringLabel>, Integer>> vertexIndexMap;
		List<Pair<DTLink<PathStringLabel,PathStringLabel>, Integer>> edgeIndexMap;
		Map<DTNode<String,String>, DTNode<PathStringLabel,PathStringLabel>> vOldNewMap = new HashMap<DTNode<String,String>,DTNode<PathStringLabel,PathStringLabel>>();
		Map<DTLink<String,String>, DTLink<PathStringLabel,PathStringLabel>> eOldNewMap = new HashMap<DTLink<String,String>,DTLink<PathStringLabel,PathStringLabel>>();

		rdfGraph = new LightDTGraph<PathStringLabel,PathStringLabel>();

		for (DTNode<String,String> oldStartV : instances) {				
			vertexIndexMap = new ArrayList<Pair<DTNode<PathStringLabel,PathStringLabel>, Integer>>();
			edgeIndexMap   = new ArrayList<Pair<DTLink<PathStringLabel,PathStringLabel>, Integer>>();

			// Get the start node
			if (vOldNewMap.containsKey(oldStartV)) {
				startV = vOldNewMap.get(oldStartV);
			} else { 
				if (!labelDict.containsKey(oldStartV.label())) {
					labelDict.put(oldStartV.label(), labelDict.size());
				}	
				startV = rdfGraph.add(new PathStringLabel("_" + Integer.toString(labelDict.get(oldStartV.label()))));
				vOldNewMap.put(oldStartV, startV);
			}
			instanceVertices.add(startV);

			instanceVertexIndexMap.put(startV, vertexIndexMap);
			instanceEdgeIndexMap.put(startV, edgeIndexMap);

			frontV = new ArrayList<DTNode<String,String>>();
			frontV.add(oldStartV);

			// Process the start node
			vertexIndexMap.add(new Pair<DTNode<PathStringLabel,PathStringLabel>,Integer>(startV, depth));
			startV.label().updateMaxDepth(depth);

			for (int j = depth - 1; j >= 0; j--) {
				newFrontV = new ArrayList<DTNode<String,String>>();
				for (DTNode<String,String> qV : frontV) {
					for (DTLink<String,String> edge : qV.linksOut()) {
						if (vOldNewMap.containsKey(edge.to())) { // This vertex has been added to rdfGraph
							vertexIndexMap.add(new Pair<DTNode<PathStringLabel,PathStringLabel>,Integer>(vOldNewMap.get(edge.to()), j));
							vOldNewMap.get(edge.to()).label().updateMaxDepth(j);
						} else {			
							if (!labelDict.containsKey(edge.to().label())) {
								labelDict.put(edge.to().label(), labelDict.size());
							}										
							DTNode<PathStringLabel,PathStringLabel> newN = rdfGraph.add(new PathStringLabel("_" + Integer.toString(labelDict.get(edge.to().label()))));
							vOldNewMap.put(edge.to(), newN);
							vertexIndexMap.add(new Pair<DTNode<PathStringLabel,PathStringLabel>,Integer>(newN, j));
							newN.label().updateMaxDepth(j);
						}

						if (eOldNewMap.containsKey(edge)) {
							edgeIndexMap.add(new Pair<DTLink<PathStringLabel,PathStringLabel>,Integer>(eOldNewMap.get(edge), j));
							eOldNewMap.get(edge).tag().updateMaxDepth(j);
						} else {
							if (!labelDict.containsKey(edge.tag())) {
								labelDict.put(edge.tag(), labelDict.size());
							}
							DTLink<PathStringLabel,PathStringLabel> newE = vOldNewMap.get(qV).connect(vOldNewMap.get(edge.to()), new PathStringLabel("_" + Integer.toString(labelDict.get(edge.tag()))));
							eOldNewMap.put(edge, newE);
							edgeIndexMap.add(new Pair<DTLink<PathStringLabel,PathStringLabel>,Integer>(newE, j));
							newE.tag().updateMaxDepth(j);
						}

						// Add the vertex to the new front, if we go into a new round
						if (j > 0) {
							newFrontV.add(edge.to());
						}
					}
				}
				frontV = newFrontV;
			}
		}		
	}





	/**
	 * The computation of the feature vectors assumes that each edge and vertex is only processed once. We can encounter the same
	 * vertex/edge on different depths during computation, this could lead to multiple counts of the same vertex, possibly of different
	 * depth labels.
	 * 
	 * @param graph
	 * @param instances
	 * @param weight
	 * @param featureVectors
	 */
	private void computeFVs(DTGraph<PathStringLabel,PathStringLabel> graph, List<DTNode<PathStringLabel,PathStringLabel>> instances, SparseVector[] featureVectors, int lastIndex, int currentIt) {
		int index, depth;
		List<Pair<DTNode<PathStringLabel,PathStringLabel>, Integer>> vertexIndexMap;
		List<Pair<DTLink<PathStringLabel,PathStringLabel>, Integer>> edgeIndexMap;

		for (int i = 0; i < instances.size(); i++) {
			featureVectors[i].setLastIndex(lastIndex);

			vertexIndexMap = instanceVertexIndexMap.get(instances.get(i));
			for (Pair<DTNode<PathStringLabel,PathStringLabel>, Integer> vertex : vertexIndexMap) {
				depth = vertex.getSecond();
				if ((depth * 2) >=  currentIt) {
					for (String path : vertex.getFirst().label().getPaths()) {
						index = pathDict.get(path);
						featureVectors[i].setValue(index, featureVectors[i].getValue(index) + 1.0);
					}
				}
			}
			edgeIndexMap = instanceEdgeIndexMap.get(instances.get(i));
			for (Pair<DTLink<PathStringLabel,PathStringLabel>, Integer> edge : edgeIndexMap) {
				depth = edge.getSecond();

				if ((depth * 2)+1 >=  currentIt) {
					for (String path : edge.getFirst().tag().getPaths()) {
						index = pathDict.get(path);
						featureVectors[i].setValue(index, featureVectors[i].getValue(index) + 1.0);
					}
				}
			}
		}
	}

	private void computeLabelFreqs(DTGraph<PathStringLabel,PathStringLabel> graph, List<DTNode<PathStringLabel,PathStringLabel>> instances) {
		List<Pair<DTNode<PathStringLabel,PathStringLabel>, Integer>> vertexIndexMap;
		List<Pair<DTLink<PathStringLabel,PathStringLabel>, Integer>> edgeIndexMap;

		// Build a new label Frequencies map
		labelFreq = new HashMap<String, Integer>();

		for (int i = 0; i < instances.size(); i++) {
			Set<String> seen = new HashSet<String>(); // to track seen label for this instance

			vertexIndexMap = instanceVertexIndexMap.get(instances.get(i));
			for (Pair<DTNode<PathStringLabel,PathStringLabel>,Integer> vertex : vertexIndexMap) {
				String lab = vertex.getFirst().label().getLabel();
				if (!labelFreq.containsKey(lab)) {
					labelFreq.put(lab, 0);
				} 
				if (!seen.contains(lab)) {
					labelFreq.put(lab, labelFreq.get(lab) + 1);
					seen.add(lab);
				}
			}
			edgeIndexMap = instanceEdgeIndexMap.get(instances.get(i));
			for (Pair<DTLink<PathStringLabel,PathStringLabel>,Integer> edge : edgeIndexMap) {
				String lab = edge.getFirst().tag().getLabel();
				if (!labelFreq.containsKey(lab)) {
					labelFreq.put(lab, 0);
				} 
				if (!seen.contains(lab)) {
					labelFreq.put(lab, labelFreq.get(lab) + 1);
					seen.add(lab);
				}
			}
		}
	}

	private void computePathFreqs(DTGraph<PathStringLabel,PathStringLabel> graph, List<DTNode<PathStringLabel,PathStringLabel>> instances, int currentIt) {
		List<Pair<DTNode<PathStringLabel,PathStringLabel>,Integer>> vertexIndexMap;
		List<Pair<DTLink<PathStringLabel,PathStringLabel>,Integer>> edgeIndexMap;
		List<String> labels;

		// Build a new label Frequencies map
		pathFreq = new HashMap<String, Integer>();

		for (int i = 0; i < instances.size(); i++) {
			Set<String> seen = new HashSet<String>(); // to track seen label for this instance

			vertexIndexMap = instanceVertexIndexMap.get(instances.get(i));
			for (Pair<DTNode<PathStringLabel,PathStringLabel>,Integer> vertex : vertexIndexMap) {
				if (vertex.getSecond() * 2 >= currentIt - 2) { // should be still in the instance.
					for (String lab : vertex.getFirst().label().getPaths()) {
						if (!pathFreq.containsKey(lab)) {
							pathFreq.put(lab, 0);
						} 
						if (!seen.contains(lab)) {
							pathFreq.put(lab, pathFreq.get(lab) + 1);
							seen.add(lab);
						}
					}
				}
			}
			edgeIndexMap = instanceEdgeIndexMap.get(instances.get(i));
			for (Pair<DTLink<PathStringLabel,PathStringLabel>,Integer> edge : edgeIndexMap) {
				if (edge.getSecond() * 2 >= currentIt - 1) { // should be still in the instance.
					for (String lab : edge.getFirst().tag().getPaths()) {
						if (!pathFreq.containsKey(lab)) {
							pathFreq.put(lab, 0);
						} 
						if (!seen.contains(lab)) {
							pathFreq.put(lab, pathFreq.get(lab) + 1);
							seen.add(lab);
						}
					}
				}
			}
		}
	}

	private class PathStringLabel {
		private String label;
		private List<String> paths;
		private List<String> newPaths;
		private int maxDepth = 0;

		public PathStringLabel(String label) {
			this.label = label;
			paths = new ArrayList<String>();
			paths.add(new String(label));
			newPaths = new ArrayList<String>();
		}

		public List<String> getPaths() {
			return paths;
		}

		public String getLabel() {
			return label;
		}

		public void addPaths(List<String> paths2) {
			String label = (labelFreq.get(this.label) > minFreq) ? this.label : ""; 

			for (String path : paths2) {
				path = (pathFreq.get(path) > minFreq) ? path : "";
				newPaths.add(label + path);			
			}
		}

		public int getMaxDepth() {
			return maxDepth;
		}

		public void updateMaxDepth(int newD) {
			maxDepth  = (newD > maxDepth) ? newD : maxDepth;
		}

		public void setNewPaths() {
			//if (!newPaths.isEmpty()) { // If we add a check on emptiness of newPaths, than we get behavior similar to standard WL. 
			//Without the check, paths will be empty in the next iteration so the same paths will not be propagated again
			paths.clear();
			paths.addAll(newPaths);
			newPaths.clear(); // clearing instead of new to save some GC
			//}					
		}
	}
}
