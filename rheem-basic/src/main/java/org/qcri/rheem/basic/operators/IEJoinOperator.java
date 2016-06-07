package org.qcri.rheem.basic.operators;

import org.qcri.rheem.basic.data.JoinCondition;
import org.qcri.rheem.basic.data.Record;
import org.qcri.rheem.basic.data.Tuple2;
import org.qcri.rheem.basic.data.Tuple5;
import org.qcri.rheem.core.plan.rheemplan.BinaryToUnaryOperator;
import org.qcri.rheem.core.types.DataSetType;

/**
 * This operator applies inequality join on elements of input datasets.
 */
public class IEJoinOperator<Type0, Type1>
        extends BinaryToUnaryOperator<Record, Record, Tuple2<Record, Record>> {

    protected final int get0Pivot;
    protected final int get1Pivot;
    protected final JoinCondition cond0;
    protected final int get0Ref;
    protected final int get1Ref;
    protected final JoinCondition cond1;
    protected boolean list1ASC;
    protected boolean list1ASCSec;
    protected boolean list2ASC;
    protected boolean list2ASCSec;
    protected boolean equalReverse;

    public IEJoinOperator(Class<Record> inputType0Class, Class<Record> inputType1Class,
                          int get0Pivot, int get1Pivot, JoinCondition cond0,
                          int get0Ref, int get1Ref, JoinCondition cond1) {
        super(DataSetType.createDefault(inputType0Class),
                DataSetType.createDefault(inputType1Class),
                DataSetType.createDefaultUnchecked(Tuple2.class),
                false);
        this.get0Pivot = get0Pivot;
        this.get1Pivot = get1Pivot;
        this.cond0 = cond0;
        this.get0Ref = get0Ref;
        this.get1Ref = get1Ref;
        this.cond1 = cond1;
        assignSortOrders();
    }

    public IEJoinOperator(DataSetType<Record> inputType0, DataSetType inputType1,
                          int get0Pivot, int get1Pivot, JoinCondition cond0,
                          int get0Ref, int get1Ref, JoinCondition cond1) {
        super(inputType0, inputType1, DataSetType.createDefaultUnchecked(Tuple2.class), false);
        this.get0Pivot = get0Pivot;
        this.get1Pivot = get1Pivot;
        this.cond0 = cond0;
        this.get0Ref = get0Ref;
        this.get1Ref = get1Ref;
        this.cond1 = cond1;
        assignSortOrders();
    }

    public void assignSortOrders() {
        Tuple5<Boolean, Boolean, Boolean, Boolean, Boolean> sortOrders = IEJoinMasterOperator.getSortOrders(this.cond0, this.cond1);
        list1ASC = sortOrders.getField0();
        list1ASCSec = sortOrders.getField1();
        list2ASC = sortOrders.getField2();
        list2ASCSec = sortOrders.getField3();
        equalReverse = sortOrders.getField4();
    }
}
