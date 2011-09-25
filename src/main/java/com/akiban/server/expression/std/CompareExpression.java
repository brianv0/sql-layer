/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.expression.std;

import com.akiban.server.error.AkibanInternalException;
import com.akiban.server.expression.Expression;
import com.akiban.server.expression.ExpressionEvaluation;
import com.akiban.server.types.AkType;
import com.akiban.server.types.ValueSource;
import com.akiban.server.types.extract.Extractors;
import com.akiban.server.types.util.BoolValueSource;
import com.akiban.server.types.util.ValueHolder;
import com.akiban.util.ArgumentValidation;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class CompareExpression extends AbstractTwoArgExpression {

    // AbstractTwoArgExpression interface
    @Override
    protected void describe(StringBuilder sb) {
        sb.append(comparison);
    }

    @Override
    public ExpressionEvaluation evaluation() {
        return new InnerEvaluation(childrenEvaluations(), comparison, op);
    }

    public CompareExpression(List<? extends Expression> children, Comparison comparison) {
        super(childrenType(children), children);
        this.comparison = comparison;
        AkType type = valueType();
        assert type != null;
        this.op = readOnlyCompareOps.get(type);
        if (this.op == null)
            throw new AkibanInternalException("couldn't find internal comparator for " + type);
    }

    // for use in this class

    private static AkType childrenType(List<? extends Expression> children) {
        Iterator<? extends Expression> iter = children.iterator();
        if (!iter.hasNext())
            throw new IllegalArgumentException("Comparison must take exatly two children expressions; none provided");
        AkType type = iter.next().valueType();
        while(iter.hasNext()) { // should only be once, but AbstractTwoArgExpression will check that
            AkType childType = iter.next().valueType();
            if (!type.equals(childType)) {
                throw new IllegalArgumentException("Comparison's children must all have same type. First child was "
                        + type + ", but then saw " + childType);
            }
        }
        return type;
    }

    private static Map<AkType,CompareOp> createCompareOpsMap() {
        Map<AkType,CompareOp> map = new EnumMap<AkType, CompareOp>(AkType.class);
        CompareOp doubleCompareOp = createDoubleCompareOp();
        for (AkType type : AkType.values()) {
            switch (type.underlyingType()) {
                case BOOLEAN_AKTYPE:
                    // TODO
                    break;
                case LONG_AKTYPE:
                    map.put(type, new LongCompareOp(type));
                    break;
                case FLOAT_AKTYPE:
                case DOUBLE_AKTYPE:
                    map.put(type, doubleCompareOp);
                    break;
                case OBJECT_AKTYPE:
                    map.put(type, new ObjectCompareOp(type));
                    break;
            }
        }
        return map;
    }

    private static CompareOp createDoubleCompareOp() {
        return new CompareOp() {
            @Override
            public int compare(ValueSource a, ValueSource b) {
                double aDouble = Extractors.getDoubleExtractor().getDouble(a);
                double bDouble = Extractors.getDoubleExtractor().getDouble(b);
                return Double.compare(aDouble, bDouble);
            }

            @Override
            public AkType opType() {
                return AkType.DOUBLE;
            }
        };
    }

    // object state

    private final Comparison comparison;
    private final CompareOp op;

    // consts

    private static final Map<AkType, CompareOp> readOnlyCompareOps = createCompareOpsMap();

    // nested classes
    public static abstract class CompareOp {
        abstract int compare(ValueSource a, ValueSource b);
        abstract AkType opType();

        private CompareOp() {}
    }

    private static class LongCompareOp extends CompareOp {
        @Override
        int compare(ValueSource a, ValueSource b) {
            long aLong = Extractors.getLongExtractor(type).getLong(a);
            long bLong = Extractors.getLongExtractor(type).getLong(b);
            return (int)(aLong - bLong);
        }

        @Override
        AkType opType() {
            return type;
        }

        private LongCompareOp(AkType type) {
            this.type = type;
            ArgumentValidation.isEQ(
                    "require underlying type", AkType.UnderlyingType.LONG_AKTYPE,
                    "given type", this.type.underlyingType()
            );
        }

        private final AkType type;
    }

    private static class ObjectCompareOp extends CompareOp {
        @Override
        int compare(ValueSource a, ValueSource b) {
            switch (type) {
            case DECIMAL:   return doCompare(a.getDecimal(), b.getDecimal());
            case VARCHAR:   return doCompare(a.getString(), b.getString());
            case TEXT:      return doCompare(a.getText(), b.getText());
            case U_BIGINT:  return doCompare(a.getUBigInt(), b.getUBigInt());
            case VARBINARY: return doCompare(a.getVarBinary(), b.getVarBinary());
            default: throw new UnsupportedOperationException("can't get comparable for type " + type);
            }
        }

        private <T extends Comparable<T>> int doCompare(T one, T two) {
            return one.compareTo(two);
        }

        @Override
        AkType opType() {
            return type;
        }

        private ObjectCompareOp(AkType type) {
            this.type = type;
            ArgumentValidation.isEQ(
                    "require underlying type", AkType.UnderlyingType.OBJECT_AKTYPE,
                    "given type", this.type.underlyingType()
            );
        }

        private final AkType type;
    }

    private static class InnerEvaluation extends AbstractTwoArgExpressionEvaluation {
        @Override
        public ValueSource eval() {
            ValueSource left = scratch.copyFrom(left());
            ValueSource right = right();
            if (left.isNull() || right.isNull())
                return BoolValueSource.OF_NULL;
            int compareResult = op.compare(left, right);
            return BoolValueSource.of(comparison.matchesCompareTo(compareResult));
        }

        private InnerEvaluation(List<? extends ExpressionEvaluation> children, Comparison comparison, CompareOp op) {
            super(children);
            this.op = op;
            this.comparison = comparison;
            this.scratch = new ValueHolder();
        }

        private final Comparison comparison;
        private final CompareOp op;
        private final ValueHolder scratch;
    }
}
