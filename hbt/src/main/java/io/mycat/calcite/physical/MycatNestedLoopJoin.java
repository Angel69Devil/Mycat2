package io.mycat.calcite.physical;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.mycat.calcite.*;
import org.apache.calcite.adapter.enumerable.EnumUtils;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMdCollation;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.BuiltInMethod;

import java.util.List;
import java.util.Set;

/**
 * Join operator implemented in Mycat convention.
 */
public class MycatNestedLoopJoin extends Join implements MycatRel {
    /**
     * Creates a MycatJoin.
     */
    protected MycatNestedLoopJoin(RelOptCluster cluster,
                                  RelTraitSet traitSet,
                                  List<RelHint> hints,
                                  RelNode left,
                                  RelNode right,
                                  RexNode condition,
                                  Set<CorrelationId> variablesSet, JoinRelType joinType) {
        super(cluster, traitSet, hints, left, right, condition, variablesSet, joinType);
    }

    public static MycatNestedLoopJoin create(RelTraitSet traitSet,
                                             RelNode left,
                                             RelNode right,
                                             RexNode condition,
                                             JoinRelType joinType) {
        RelOptCluster cluster = left.getCluster();
        RelMetadataQuery mq = cluster.getMetadataQuery();
        traitSet = traitSet.replace(MycatConvention.INSTANCE)
                .replaceIfs(RelCollationTraitDef.INSTANCE, () -> {
                    return RelMdCollation.enumerableNestedLoopJoin(mq, left, right, joinType);
                });
        return new MycatNestedLoopJoin(cluster,
                traitSet,
                ImmutableList.of(),
                left,
                right,
                condition,
                ImmutableSet.of(),
                joinType);
    }

    @Override
    public MycatNestedLoopJoin copy(RelTraitSet traitSet, RexNode condition,
                                    RelNode left, RelNode right, JoinRelType joinType,
                                    boolean semiJoinDone) {
        return new MycatNestedLoopJoin(getCluster(), traitSet, getHints(), left, right,
                condition, variablesSet, joinType);
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner,
                                      RelMetadataQuery mq) {
        // We always "build" the
        double rowCount = mq.getRowCount(this);

        return planner.getCostFactory().makeCost(rowCount, 0, 0);
    }

    @Override
    public double estimateRowCount(RelMetadataQuery mq) {
        final double leftRowCount = left.estimateRowCount(mq);
        final double rightRowCount = right.estimateRowCount(mq);
        return Math.max(leftRowCount, rightRowCount);
    }

    @Override
    public ExplainWriter explain(ExplainWriter writer) {
        writer.name("MycatJoin").item("joinType", joinType).item("condition", condition).into();
        for (RelNode input : getInputs()) {
            MycatRel rel = (MycatRel) input;
            rel.explain(writer);
        }
        return writer.ret();
    }

    @Override
    public Executor implement(ExecutorImplementor implementor) {
        return implementor.implement(this);
    }

    @Override
    public Result implement(MycatEnumerableRelImplementor implementor, Prefer pref) {
        final BlockBuilder builder = new BlockBuilder();
        final Result leftResult =
                implementor.visitChild(this, 0, (EnumerableRel) left, pref);
        Expression leftExpression =
                toEnumerate(builder.append("left", leftResult.block));
        final Result rightResult =
                implementor.visitChild(this, 1, (EnumerableRel) right, pref);
        Expression rightExpression =
                toEnumerate(builder.append("right", rightResult.block));
        final PhysType physType =
                PhysTypeImpl.of(implementor.getTypeFactory(),
                        getRowType(),
                        pref.preferArray());
        final Expression predicate =
                EnumUtils.generatePredicate(implementor, getCluster().getRexBuilder(), left, right,
                        leftResult.physType, rightResult.physType, condition);
        return implementor.result(
                physType,
                builder.append(
                        Expressions.call(BuiltInMethod.NESTED_LOOP_JOIN.method,
                                leftExpression,
                                rightExpression,
                                predicate,
                                EnumUtils.joinSelector(joinType,
                                        physType,
                                        ImmutableList.of(leftResult.physType,
                                                rightResult.physType)),
                                Expressions.constant(EnumUtils.toLinq4jJoinType(joinType))))
                        .toBlock());
    }
    @Override
    public boolean isSupportStream() {
        return false;
    }
}