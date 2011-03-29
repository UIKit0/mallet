/* Copyright (C) 2010 Univ. of Massachusetts Amherst, Computer Science Dept.
   This file is part of "MALLET" (MAchine Learning for LanguagE Toolkit).
   http://www.cs.umass.edu/~mccallum/mallet
   This software is provided under the terms of the Common Public License,
   version 1.0, as published by http://www.opensource.org.  For further
   information, see the file `LICENSE' included with this distribution. */

package cc.mallet.fst.semi_supervised.constraints;

import gnu.trove.TIntArrayList;
import gnu.trove.TIntObjectHashMap;

import java.util.ArrayList;
import java.util.BitSet;

import cc.mallet.fst.SumLattice;
import cc.mallet.fst.SumLatticeDefault;
import cc.mallet.fst.semi_supervised.StateLabelMap;
import cc.mallet.types.FeatureVector;
import cc.mallet.types.FeatureVectorSequence;
import cc.mallet.types.Instance;
import cc.mallet.types.InstanceList;

/**
 * A set of constraints on distributions over single
 * labels conditioned on the presence of input features.  
 * 
 * Multiple constraints are grouped together here
 * to make things more efficient.
 * 
 * @author Gregory Druck
 */

public abstract class OneLabelGEConstraints implements GEConstraint {

  // maps between input feature indices and constraints
  protected TIntObjectHashMap<OneLabelGEConstraint> constraints;
  protected StateLabelMap map;
  
  // cache of set of constrained features that fire at last FeatureVector
  // provided in preprocess call
  protected TIntArrayList cache;

  public OneLabelGEConstraints() {
    this.constraints = new TIntObjectHashMap<OneLabelGEConstraint>();
    this.cache = new TIntArrayList();
  }
  
  protected OneLabelGEConstraints(TIntObjectHashMap<OneLabelGEConstraint> constraints, StateLabelMap map) {
    this.constraints = constraints;
    this.map = map;
    this.cache = new TIntArrayList();
  }
  
  public abstract void addConstraint(int fi, double[] target, double weight);
  
  public boolean isOneStateConstraint() {
    return true;
  }
  
  public void setStateLabelMap(StateLabelMap map) {
    this.map = map;
  }
  
  public void preProcess(FeatureVector fv) {
    cache.resetQuick();
    int fi;
    // cache constrained input features
    for (int loc = 0; loc < fv.numLocations(); loc++) {
      fi = fv.indexAtLocation(loc);
      if (constraints.containsKey(fi)) {
        cache.add(fi);
      }
    }
  }
  
  // find examples that contain constrained input features
  public BitSet preProcess(InstanceList data) {
    // count
    int ii = 0;
    BitSet bitSet = new BitSet(data.size());
    for (Instance instance : data) {
      FeatureVectorSequence fvs = (FeatureVectorSequence)instance.getData();
      for (int ip = 0; ip < fvs.size(); ip++) {
        for (int fi : constraints.keys()) {
          // binary constraint features
          if (fvs.get(ip).location(fi) >= 0) {
            constraints.get(fi).count += 1;
            bitSet.set(ii);
          }
        }
      }
      ii++;
    }
    return bitSet;
  }    
  
  public double getConstraintFeatureValue(FeatureVector fv, int ip, int si1, int si2) {
    double value = 0;
    int li2 = map.getLabelIndex(si2);
    for (int i = 0; i < cache.size(); i++) {
      value += constraints.get(cache.getQuick(i)).getValue(li2);
    }
    return value;
  }

  public abstract double getValue();

  public void zeroExpectations() {
    for (int fi : constraints.keys()) {
      constraints.get(fi).expectation = new double[map.getNumLabels()];
    }
  }
  
  public void computeExpectations(ArrayList<SumLatticeDefault> lattices, InstanceList data) {
    double[][] gammas;
    for (int i = 0; i < lattices.size(); i++) {
      FeatureVectorSequence fvs = (FeatureVectorSequence)data.get(i).getData();
      SumLattice lattice = lattices.get(i);
      if (lattice == null) { continue; }
      gammas = lattice.getGammas();
      for (int ip = 0; ip < fvs.size(); ++ip) {
        for (int s = 0; s < map.getNumStates(); ++s) {
          int li = map.getLabelIndex(s);
          if (li != StateLabelMap.START_LABEL) {
            double gammaProb = Math.exp(gammas[ip+1][s]);
            for (int fi : constraints.keys()) {
              OneLabelGEConstraint constraint = constraints.get(fi);
              // binary constraint features
              if (fvs.getFeatureVector(ip).value(fi) > 0.0) {
                constraint.expectation[li] += gammaProb;
              }
            }
          }
        }
      }
    }
  }
  
  protected abstract class OneLabelGEConstraint {
    
    protected double[] target;
    protected double[] expectation;
    protected double count;
    protected double weight;
    
    public OneLabelGEConstraint(double[] target, double weight) {
      this.target = target;
      this.weight = weight;
      this.expectation = null;
      this.count = 0;
    }
    
    public abstract double getValue(int li);
  }
}