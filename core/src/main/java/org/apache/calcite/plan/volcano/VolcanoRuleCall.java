/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.plan.volcano;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.calcite.plan.*;
import org.apache.calcite.rel.RelNode;

import java.util.*;

/**
 * <code>VolcanoRuleCall</code> implements the {@link RelOptRuleCall} interface
 * for VolcanoPlanner.
 */
public class VolcanoRuleCall extends RelOptRuleCall {
    //~ Instance fields --------------------------------------------------------

    protected final VolcanoPlanner volcanoPlanner;

    /**
     * List of {@link RelNode} generated by this call. For debugging purposes.
     */
    private List<RelNode> generatedRelList;

    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a rule call, internal, with array to hold bindings.
     *
     * @param planner    Planner
     * @param operand    First operand of the rule
     * @param rels       Array which will hold the matched relational expressions
     * @param nodeInputs For each node which matched with {@code matchAnyChildren}
     *                   = true, a list of the node's inputs
     */
    protected VolcanoRuleCall(VolcanoPlanner planner, RelOptRuleOperand operand, RelNode[] rels,
                              Map<RelNode, List<RelNode>> nodeInputs) {
        super(planner, operand, rels, nodeInputs);
        this.volcanoPlanner = planner;
    }

    /**
     * Creates a rule call.
     *
     * @param planner Planner
     * @param operand First operand of the rule
     */
    VolcanoRuleCall(VolcanoPlanner planner, RelOptRuleOperand operand) {
        this(planner, operand, new RelNode[operand.getRule().operands.size()],
             ImmutableMap.<RelNode, List<RelNode>>of());
    }

    //~ Methods ----------------------------------------------------------------

    // implement RelOptRuleCall
    public void transformTo(RelNode rel, Map<RelNode, RelNode> equiv) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Transform to: rel#{} via {}{}", rel.getId(), getRule(),
                         equiv.isEmpty() ? "" : " with equivalences " + equiv);
            if (generatedRelList != null) {
                generatedRelList.add(rel);
            }
        }
        try {
            // It's possible that rel is a subset or is already registered.
            // Is there still a point in continuing? Yes, because we might
            // discover that two sets of expressions are actually equivalent.

            // Make sure traits that the new rel doesn't know about are
            // propagated.
            RelTraitSet rels0Traits = rels[0].getTraitSet();
            new RelTraitPropagationVisitor(getPlanner(), rels0Traits).go(rel);

            if (LOGGER.isTraceEnabled()) {
                // Cannot call RelNode.toString() yet, because rel has not
                // been registered. For now, let's make up something similar.
                String relDesc = "rel#" + rel.getId() + ":" + rel.getRelTypeName();
                LOGGER.trace("call#{}: Rule {} arguments {} created {}", id, getRule(), Arrays.toString(rels), relDesc);
            }

            if (volcanoPlanner.listener != null) {
                RelOptListener.RuleProductionEvent event = new RelOptListener.RuleProductionEvent(volcanoPlanner, rel,
                                                                                                  this, true);
                volcanoPlanner.listener.ruleProductionSucceeded(event);
            }

            // Registering the root relational expression implicitly registers
            // its descendants. Register any explicit equivalences first, so we
            // don't register twice and cause churn.
            for (Map.Entry<RelNode, RelNode> entry : equiv.entrySet()) {
                volcanoPlanner.ensureRegistered(entry.getKey(), entry.getValue(), this);
            }
            volcanoPlanner.ensureRegistered(rel, rels[0], this);
            rels[0].getCluster().invalidateMetadataQuery();

            if (volcanoPlanner.listener != null) {
                RelOptListener.RuleProductionEvent event = new RelOptListener.RuleProductionEvent(volcanoPlanner, rel,
                                                                                                  this, false);
                volcanoPlanner.listener.ruleProductionSucceeded(event);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error occurred while applying rule " + getRule(), e);
        }
    }

    /**
     * Called when all operands have matched.
     */
    protected void onMatch() {
        assert getRule().matches(this);
        volcanoPlanner.checkCancel();
        try {
            if (volcanoPlanner.isRuleExcluded(getRule())) {
                LOGGER.debug("Rule [{}] not fired due to exclusion filter", getRule());
                return;
            }

            for (int i = 0; i < rels.length; i++) {
                RelNode rel = rels[i];
                RelSubset subset = volcanoPlanner.getSubset(rel);

                if (subset == null) {
                    LOGGER.debug("Rule [{}] not fired because operand #{} ({}) has no subset", getRule(), i, rel);
                    return;
                }

                if (subset.set.equivalentSet != null) {
                    LOGGER.debug("Rule [{}] not fired because operand #{} ({}) belongs to obsolete set", getRule(), i,
                                 rel);
                    return;
                }

                final Double importance = volcanoPlanner.relImportances.get(rel);
                if ((importance != null) && (importance == 0d)) {
                    LOGGER.debug("Rule [{}] not fired because operand #{} ({}) has importance=0", getRule(), i, rel);
                    return;
                }
            }

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("call#{}: Apply rule [{}] to {}", id, getRule(), Arrays.toString(rels));
            }

            if (volcanoPlanner.listener != null) {
                RelOptListener.RuleAttemptedEvent event = new RelOptListener.RuleAttemptedEvent(volcanoPlanner, rels[0],
                                                                                                this, true);
                volcanoPlanner.listener.ruleAttempted(event);
            }

            if (LOGGER.isDebugEnabled()) {
                this.generatedRelList = new ArrayList<>();
            }

            getRule().onMatch(this);

            if (LOGGER.isDebugEnabled()) {
                if (generatedRelList.isEmpty()) {
                    LOGGER.debug("call#{} generated 0 successors.", id);
                } else {
                    LOGGER.debug("call#{} generated {} successors: {}", id, generatedRelList.size(), generatedRelList);
                }
                this.generatedRelList = null;
            }

            if (volcanoPlanner.listener != null) {
                RelOptListener.RuleAttemptedEvent event = new RelOptListener.RuleAttemptedEvent(volcanoPlanner, rels[0],
                                                                                                this, false);
                volcanoPlanner.listener.ruleAttempted(event);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error while applying rule " + getRule() + ", args " + Arrays.toString(rels), e);
        }
    }

    /**
     * Applies this rule, with a given relational expression in the first slot.
     */
    void match(RelNode rel) {
        assert getOperand0().matches(rel) : "precondition";
        final int solve = 0;
        int operandOrdinal = getOperand0().solveOrder[solve];
        this.rels[operandOrdinal] = rel;
        matchRecurse(solve + 1);
    }

    /**
     * Recursively matches operands above a given solve order.
     *
     * @param solve Solve order of operand (&gt; 0 and &le; the operand count)
     */
    private void matchRecurse(int solve) {
        assert solve > 0;
        assert solve <= rule.operands.size();
        final List<RelOptRuleOperand> operands = getRule().operands;
        if (solve == operands.size()) {
            // We have matched all operands. Now ask the rule whether it
            // matches; this gives the rule chance to apply side-conditions.
            // If the side-conditions are satisfied, we have a match.
            if (getRule().matches(this)) {
                onMatch();
            }
        } else {
            final int operandOrdinal = operand0.solveOrder[solve];
            final int previousOperandOrdinal = operand0.solveOrder[solve - 1];
            boolean ascending = operandOrdinal < previousOperandOrdinal;
            final RelOptRuleOperand previousOperand = operands.get(previousOperandOrdinal);
            final RelOptRuleOperand operand = operands.get(operandOrdinal);
            final RelNode previous = rels[previousOperandOrdinal];

            final RelOptRuleOperand parentOperand;
            final Collection<? extends RelNode> successors;
            if (ascending) {
                assert previousOperand.getParent() == operand;
                parentOperand = operand;
                final RelSubset subset = volcanoPlanner.getSubset(previous);
                successors = subset.getParentRels();
            } else {
                parentOperand = previousOperand;
                final int parentOrdinal = operand.getParent().ordinalInRule;
                final RelNode parentRel = rels[parentOrdinal];
                final List<RelNode> inputs = parentRel.getInputs();
                if (operand.ordinalInParent < inputs.size()) {
                    final RelSubset subset = (RelSubset) inputs.get(operand.ordinalInParent);
                    if (operand.getMatchedClass() == RelSubset.class) {
                        successors = subset.set.subsets;
                    } else {
                        successors = subset.getRelList();
                    }
                } else {
                    // The operand expects parentRel to have a certain number
                    // of inputs and it does not.
                    successors = ImmutableList.of();
                }
            }

            for (RelNode rel : successors) {
                if (!operand.matches(rel)) {
                    continue;
                }
                if (ascending) {
                    // We know that the previous operand was *a* child of its parent,
                    // but now check that it is the *correct* child.
                    final RelSubset input = (RelSubset) rel.getInput(previousOperand.ordinalInParent);
                    List<RelNode> inputRels = input.set.getRelsFromAllSubsets();
                    if (!inputRels.contains(previous)) {
                        continue;
                    }
                }

                // Assign "childRels" if the operand is UNORDERED.
                switch (parentOperand.childPolicy) {
                    case UNORDERED:
                        if (ascending) {
                            final List<RelNode> inputs = Lists.newArrayList(rel.getInputs());
                            inputs.set(previousOperand.ordinalInParent, previous);
                            setChildRels(rel, inputs);
                        } else {
                            List<RelNode> inputs = getChildRels(previous);
                            if (inputs == null) {
                                inputs = Lists.newArrayList(previous.getInputs());
                            }
                            inputs.set(operand.ordinalInParent, rel);
                            setChildRels(previous, inputs);
                        }
                }

                rels[operandOrdinal] = rel;
                matchRecurse(solve + 1);
            }
        }
    }
}

// End VolcanoRuleCall.java
