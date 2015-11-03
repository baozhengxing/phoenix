package org.apache.phoenix.calcite.rel;

import java.util.List;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.metadata.RelMdCollation;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.compile.RowProjector;
import org.apache.phoenix.compile.OrderByCompiler.OrderBy;
import org.apache.phoenix.execute.ClientScanPlan;

import com.google.common.base.Supplier;

public class PhoenixLimit extends SingleRel implements PhoenixRel {
    public final RexNode offset;
    public final RexNode fetch;
    
    public static PhoenixLimit create(final RelNode input, RexNode offset, RexNode fetch) {
        RelOptCluster cluster = input.getCluster();
        final RelTraitSet traits =
                cluster.traitSet().replace(PhoenixRel.CLIENT_CONVENTION)
                .replaceIfs(RelCollationTraitDef.INSTANCE,
                        new Supplier<List<RelCollation>>() {
                    public List<RelCollation> get() {
                        return RelMdCollation.limit(input);
                    }
                });
        return new PhoenixLimit(cluster, traits, input, offset, fetch);
    }

    private PhoenixLimit(RelOptCluster cluster, RelTraitSet traits, RelNode input, RexNode offset, RexNode fetch) {
        super(cluster, traits, input);
        this.offset = offset;
        this.fetch = fetch;
    }
    
    @Override
    public PhoenixLimit copy(
            RelTraitSet traitSet,
            List<RelNode> newInputs) {
        return create(
                sole(newInputs),
                offset,
                fetch);
    }

    @Override 
    public RelWriter explainTerms(RelWriter pw) {
        return super.explainTerms(pw)
                .itemIf("offset", offset, offset != null)
                .itemIf("fetch", fetch, fetch != null);
    }

    @Override 
    public RelOptCost computeSelfCost(RelOptPlanner planner) {
        if (getInput().getConvention() != PhoenixRel.CLIENT_CONVENTION)
            return planner.getCostFactory().makeInfiniteCost();
        
        double rowCount = RelMetadataQuery.getRowCount(this);
        return planner.getCostFactory()
                .makeCost(rowCount, 0, 0)
                .multiplyBy(PHOENIX_FACTOR);
    }
    
    @Override 
    public double getRows() {
        double rows = super.getRows();        
        return Math.min(RexLiteral.intValue(fetch), rows);
    }

    @Override
    public QueryPlan implement(Implementor implementor) {
        QueryPlan plan = implementor.visitInput(0, (PhoenixRel) getInput());
        int fetchValue = RexLiteral.intValue(fetch);
        if (plan.getLimit() == null) {
            return plan.limit(fetchValue);
        }
        
        return new ClientScanPlan(plan.getContext(), plan.getStatement(), 
                implementor.getTableRef(), RowProjector.EMPTY_PROJECTOR, 
                fetchValue, null, OrderBy.EMPTY_ORDER_BY, plan);
    }
}