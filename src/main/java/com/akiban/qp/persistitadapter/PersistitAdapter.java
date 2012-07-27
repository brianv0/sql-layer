/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.qp.persistitadapter;

import com.akiban.ais.model.GroupTable;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.PrimaryKey;
import com.akiban.ais.model.Sequence;
import com.akiban.ais.model.UserTable;
import com.akiban.qp.expression.IndexKeyRange;
import com.akiban.qp.operator.*;
import com.akiban.qp.persistitadapter.indexrow.PersistitIndexRow;
import com.akiban.qp.persistitadapter.indexrow.PersistitIndexRowBuffer;
import com.akiban.qp.row.HKey;
import com.akiban.qp.row.Row;
import com.akiban.qp.row.RowBase;
import com.akiban.qp.rowtype.IndexRowType;
import com.akiban.qp.rowtype.RowType;
import com.akiban.qp.rowtype.Schema;
import com.akiban.server.PersistitKeyValueSource;
import com.akiban.server.api.dml.scan.NewRow;
import com.akiban.server.api.dml.scan.NiceRow;
import com.akiban.server.collation.AkCollator;
import com.akiban.server.error.DuplicateKeyException;
import com.akiban.server.error.InvalidOperationException;
import com.akiban.server.error.PersistitAdapterException;
import com.akiban.server.error.QueryCanceledException;
import com.akiban.server.rowdata.RowData;
import com.akiban.server.rowdata.RowDef;
import com.akiban.server.service.config.ConfigurationService;
import com.akiban.server.service.session.Session;
import com.akiban.server.service.tree.TreeService;
import com.akiban.server.store.PersistitStore;
import com.akiban.server.store.Store;
import com.akiban.server.types.AkType;
import com.akiban.server.types.FromObjectValueSource;
import com.akiban.server.types.ToObjectValueTarget;
import com.akiban.server.types.ValueSource;
import com.akiban.util.tap.InOutTap;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.persistit.Exchange;
import com.persistit.Key;
import com.persistit.Transaction;
import com.persistit.exception.PersistitException;
import com.persistit.exception.PersistitInterruptedException;

import java.io.InterruptedIOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PersistitAdapter extends StoreAdapter
{
    private static final Logger logger = LoggerFactory.getLogger(PersistitAdapter.class);
    // StoreAdapter interface

    @Override
    public GroupCursor newGroupCursor(GroupTable groupTable)
    {
        GroupCursor cursor;
        try {
            cursor = new PersistitGroupCursor(this, groupTable);
        } catch (PersistitException e) {
            handlePersistitException(e);
            throw new AssertionError();
        }
        return cursor;
    }

    @Override
    public Cursor newIndexCursor(QueryContext context, Index index, IndexKeyRange keyRange, API.Ordering ordering,
                                 IndexScanSelector selector, boolean usePValues)
    {
        Cursor cursor;
        try {
            cursor = new PersistitIndexCursor(context, schema.indexRowType(index), keyRange, ordering,
                    selector, usePValues);
        } catch (PersistitException e) {
            handlePersistitException(e);
            throw new AssertionError();
        }
        return cursor;
    }

    @Override
    public Store getUnderlyingStore() {
        return store;
    }

    @Override
    public Cursor sort(QueryContext context,
                       Cursor input,
                       RowType rowType,
                       API.Ordering ordering,
                       API.SortOption sortOption,
                       InOutTap loadTap,
                       boolean usePValues)
    {
        return new SorterToCursorAdapter(this, context, input, rowType, ordering, sortOption, loadTap, usePValues);
    }

    @Override
    public HKey newHKey(com.akiban.ais.model.HKey hKeyMetadata)
    {
        return new PersistitHKey(this, hKeyMetadata);
    }

    @Override
    public void updateRow(Row oldRow, Row newRow) {
        RowDef rowDef = oldRow.rowType().userTable().rowDef();
        RowDef rowDefNewRow = newRow.rowType().userTable().rowDef();
        if (rowDef != rowDefNewRow) {
            throw new IllegalArgumentException(String.format("%s != %s", rowDef, rowDefNewRow));
        }

        RowData oldRowData = oldRowData(rowDef, oldRow);
        int oldStep = 0;
        try {
            // For Update row, the new row (value being inserted) does not 
            // need the default value (including identity set)
            RowData newRowData = oldRowData(rowDef, newRow);
            oldStep = enterUpdateStep();
            store.updateRow(getSession(), oldRowData, newRowData, null);
        } catch (InvalidOperationException e) {
            rollbackIfNeeded(e);
            throw e;
        } catch (PersistitException e) {
            rollbackIfNeeded(e);
            handlePersistitException(e);
            assert false;
        }
        finally {
            leaveUpdateStep(oldStep);
        }
    }
    @Override
    public void writeRow (Row newRow) {
        RowDef rowDef = newRow.rowType().userTable().rowDef();
        int oldStep = 0;
        try {
            RowData newRowData = newRowData (rowDef, newRow);
            oldStep = enterUpdateStep();
            store.writeRow(getSession(), newRowData);
        } catch (InvalidOperationException e) {
            rollbackIfNeeded(e);
            throw e;
        } catch (PersistitException e) {
            rollbackIfNeeded(e);
            handlePersistitException(e);
            assert false;
        }
        finally {
            leaveUpdateStep(oldStep);
        }
    }
    
    @Override
    public void deleteRow (Row oldRow) {
        RowDef rowDef = oldRow.rowType().userTable().rowDef();
        RowData oldRowData = oldRowData(rowDef, oldRow);
        int oldStep = enterUpdateStep();
        try {
            store.deleteRow(getSession(), oldRowData);
        } catch (InvalidOperationException e) {
            rollbackIfNeeded(e);
            throw e;
        } catch (PersistitException e) {
            rollbackIfNeeded(e);
            handlePersistitException(e);
            assert false;
        }
        finally {
            leaveUpdateStep(oldStep);
        }
    }

    @Override
    public long rowCount(RowType tableType) {
        RowDef rowDef = tableType.userTable().rowDef();
        try {
            return rowDef.getTableStatus().getRowCount();
        } catch(PersistitInterruptedException e) {
            throw new QueryCanceledException(getSession());
        }
    }

    @Override
    public long hash(ValueSource valueSource, AkCollator collator)
    {
        assert collator != null; // Caller should have hashed in this case
        long hash;
        Key key;
        int depth;
        if (valueSource instanceof PersistitKeyValueSource) {
            PersistitKeyValueSource persistitKeyValueSource = (PersistitKeyValueSource) valueSource;
            key = persistitKeyValueSource.key();
            depth = persistitKeyValueSource.depth();
        } else {
            key = persistit.getKey();
            collator.append(key, valueSource.getString());
            depth = 0;
        }
        hash = keyHasher.hash(key, depth);
        return hash;
    }

    // PersistitAdapter interface

    public PersistitStore persistit()
    {
        return persistit;
    }

    public RowDef rowDef(int tableId)
    {
        return persistit.getRowDefCache().getRowDef(tableId);
    }

    public NewRow newRow(RowDef rowDef)
    {
        NiceRow row = new NiceRow(rowDef.getRowDefId(), rowDef);
        UserTable table = rowDef.userTable();
        PrimaryKey primaryKey = table.getPrimaryKeyIncludingInternal();
        if (primaryKey != null && table.getPrimaryKey() == null) {
            // Akiban-generated PK. Initialize its value to a dummy value, which will be replaced later. The
            // important thing is that the value be non-null.
            row.put(table.getColumnsIncludingInternal().size() - 1, -1L);
        }
        return row;
    }

    public PersistitGroupRow newGroupRow()
    {
        return PersistitGroupRow.newPersistitGroupRow(this);
    }

    public PersistitIndexRowBuffer newIndexRow(Index index, Key key)
    {
        return new PersistitIndexRowBuffer(key);
    }

    public PersistitIndexRow newIndexRow(IndexRowType indexRowType)
    {
        return
            indexRowType.index().isTableIndex()
            ? PersistitIndexRow.tableIndexRow(this, indexRowType)
            : PersistitIndexRow.groupIndexRow(this, indexRowType);
    }


    public Exchange takeExchange(GroupTable table) throws PersistitException
    {
        return persistit.getExchange(getSession(), table.rowDef());
    }

    public Exchange takeExchange(Index index)
    {
        return persistit.getExchange(getSession(), index);
    }

    public Key newKey()
    {
        return new Key(persistit.getDb());
    }

    public void handlePersistitException(PersistitException e)
    {
        handlePersistitException(getSession(), e);
    }

    public static boolean isFromInterruption(Exception e) {
        Throwable cause = e.getCause();
        return (e instanceof PersistitInterruptedException) ||
               ((cause != null) && (cause instanceof InterruptedIOException || cause instanceof InterruptedException));
    }

    public static void handlePersistitException(Session session, PersistitException e)
    {
        assert e != null;
        if (isFromInterruption(e)) {
            throw new QueryCanceledException(session);
        } else {
            throw new PersistitAdapterException(e);
        }
    }

    public void returnExchange(Exchange exchange)
    {
        persistit.releaseExchange(getSession(), exchange);
    }
    
    public Transaction transaction() {
        return treeService.getTransaction(getSession());
    }

    public int enterUpdateStep()
    {
        Transaction transaction = transaction();
        int step = transaction.getStep();
        if (step > 0 && withStepChanging)
            transaction.incrementStep();
        return step;
    }

    public void leaveUpdateStep(int step) {
        Transaction txn = transaction();
        if(txn.isActive() && !txn.isRollbackPending()) {
            txn.setStep(step);
        }
    }

    public PersistitAdapter(Schema schema,
                            Store store,
                            TreeService treeService,
                            Session session,
                            ConfigurationService config)
    {
        this(schema, store, treeService, session, config, true);
    }

    public PersistitAdapter(Schema schema,
                            Store store,
                            TreeService treeService,
                            Session session,
                            ConfigurationService config,
                            boolean withStepChanging)
    {
        super(schema, session, config);
        this.store = store;
        this.persistit = store.getPersistitStore();
        assert this.persistit != null : store;
        this.treeService = treeService;
        this.withStepChanging = withStepChanging;
    }

    // For use by this class

    private RowData oldRowData (RowDef rowDef, RowBase row) {
        if (row instanceof PersistitGroupRow) {
            return ((PersistitGroupRow) row).rowData();
        }
        ToObjectValueTarget target = new ToObjectValueTarget();
        NewRow niceRow = newRow(rowDef);
        for(int i = 0; i < row.rowType().nFields(); ++i) {
            ValueSource source = row.eval(i);
            niceRow.put(i, target.convertFromSource(source));
        }
        return niceRow.toRowData();
    }

    private RowData newRowData(RowDef rowDef, RowBase row) throws PersistitException
    {
        if (row instanceof PersistitGroupRow) {
            return ((PersistitGroupRow) row).rowData();
        }
        ToObjectValueTarget target = new ToObjectValueTarget();
        NewRow niceRow = newRow(rowDef);
        for(int i = 0; i < row.rowType().nFields(); ++i) {
            ValueSource source = row.eval(i);

            // this is the generated always case. Always override the value in the
            // row
            if (rowDef.table().getColumn(i).getDefaultIdentity() != null &&
                rowDef.table().getColumn(i).getDefaultIdentity().booleanValue() == false) {
                long value = rowDef.table().getColumn(i).getIdentityGenerator().nextValue(treeService);
                FromObjectValueSource objectSource = new FromObjectValueSource();
                objectSource.setExplicitly(value, AkType.LONG);
                source = objectSource;
            }

            if (source.isNull()) {
                if (rowDef.table().getColumn(i).getIdentityGenerator() != null) {
                    Sequence sequence= rowDef.table().getColumn(i).getIdentityGenerator();
                    long value = sequence.nextValue(treeService);
                    FromObjectValueSource objectSource = new FromObjectValueSource();
                    objectSource.setExplicitly(value, AkType.LONG);
                    source = objectSource;
                }
                // TODO: If not an identityGenerator, insert the column default value.
            }

            // TODO: Validate column Check Constraints.
            niceRow.put(i, target.convertFromSource(source));
        }
        return niceRow.toRowData();
    }

    private void rollbackIfNeeded(Exception e) {
        if((e instanceof DuplicateKeyException) || (e instanceof PersistitException) || isFromInterruption(e)) {
            Transaction txn = transaction();
            if(txn.isActive()) {
                txn.rollback();
            }
        }
    }

    // Object state

    private final TreeService treeService;
    private final Store store;
    private final PersistitStore persistit;
    private final boolean withStepChanging;
    private final PersistitKeyHasher keyHasher = new PersistitKeyHasher();
}
