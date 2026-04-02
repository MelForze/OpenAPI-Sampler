package burp.openapi;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellEditor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OpenApiParserTableTest
{
    @Test
    void setOperationsAndVisibleOperationsWork() throws Exception
    {
        OpenApiParserTable table = new OpenApiParserTable();
        List<OpenApiParserModel.OperationContext> operations = List.of(op("GET", "/users"), op("POST", "/users"));

        onEdt(() -> table.setOperations(operations));

        assertEquals(2, table.visibleOperations().size());
        assertEquals("/users", table.visibleOperations().get(0).path());
    }

    @Test
    void methodAndServerAreFirstColumns() throws Exception
    {
        OpenApiParserTable parserTable = new OpenApiParserTable();
        JTable jTable = tableOf(parserTable);

        assertEquals("Method", jTable.getColumnName(0));
        assertEquals("Server", jTable.getColumnName(1));
        assertEquals("Path", jTable.getColumnName(2));
    }

    @Test
    void canHideAndShowColumn() throws Exception
    {
        OpenApiParserTable parserTable = new OpenApiParserTable();
        JTable jTable = tableOf(parserTable);
        int initialColumns = jTable.getColumnModel().getColumnCount();

        Method setColumnVisible = OpenApiParserTable.class.getDeclaredMethod("setColumnVisible", int.class, boolean.class);
        setColumnVisible.setAccessible(true);

        onEdt(() -> invokeSetColumnVisible(setColumnVisible, parserTable, 3, false));
        assertEquals(initialColumns - 1, jTable.getColumnModel().getColumnCount());
        assertEquals(-1, jTable.convertColumnIndexToView(3));

        onEdt(() -> invokeSetColumnVisible(setColumnVisible, parserTable, 3, true));
        assertEquals(initialColumns, jTable.getColumnModel().getColumnCount());
        assertTrue(jTable.convertColumnIndexToView(3) >= 0);
    }

    @Test
    void cannotHideLastVisibleColumn() throws Exception
    {
        OpenApiParserTable parserTable = new OpenApiParserTable();
        JTable jTable = tableOf(parserTable);
        Method setColumnVisible = OpenApiParserTable.class.getDeclaredMethod("setColumnVisible", int.class, boolean.class);
        setColumnVisible.setAccessible(true);

        int totalColumns = jTable.getModel().getColumnCount();
        onEdt(() -> {
            for (int modelIndex = 1; modelIndex < totalColumns; modelIndex++)
            {
                invokeSetColumnVisible(setColumnVisible, parserTable, modelIndex, false);
            }
        });

        assertEquals(1, jTable.getColumnModel().getColumnCount());
        assertTrue(jTable.convertColumnIndexToView(0) >= 0);
        assertFalse(jTable.convertColumnIndexToView(1) >= 0);

        onEdt(() -> invokeSetColumnVisible(setColumnVisible, parserTable, 0, false));
        assertEquals(1, jTable.getColumnModel().getColumnCount());
        assertTrue(jTable.convertColumnIndexToView(0) >= 0);
    }

    @Test
    void selectAllAndClearSelectionWork() throws Exception
    {
        OpenApiParserTable parserTable = new OpenApiParserTable();
        List<OpenApiParserModel.OperationContext> operations = List.of(op("GET", "/a"), op("POST", "/b"), op("DELETE", "/c"));

        onEdt(() -> parserTable.setOperations(operations));
        onEdt(parserTable::selectAllRows);
        List<OpenApiParserModel.OperationContext> selected = parserTable.selectedOperations();
        assertEquals(3, selected.size());

        onEdt(parserTable::clearSelection);
        assertEquals(0, parserTable.selectedOperations().size());
    }

    @Test
    void firstSelectedOperationAndSelectionChangedCallbackWork() throws Exception
    {
        OpenApiParserTable parserTable = new OpenApiParserTable();
        onEdt(() -> parserTable.setOperations(List.of(op("GET", "/users"), op("POST", "/orders"))));

        AtomicReference<OpenApiParserModel.OperationContext> callbackSelection = new AtomicReference<>();
        onEdt(() -> parserTable.setSelectionChangedListener(callbackSelection::set));

        JTable jTable = tableOf(parserTable);
        onEdt(() -> jTable.setRowSelectionInterval(1, 1));

        OpenApiParserModel.OperationContext firstSelected = parserTable.firstSelectedOperation();
        assertNotNull(firstSelected);
        assertEquals("/orders", firstSelected.path());
        assertNotNull(callbackSelection.get());
        assertEquals("/orders", callbackSelection.get().path());
    }

    @Test
    void rowButtonActionInvokesListenerWithOperation() throws Exception
    {
        OpenApiParserTable parserTable = new OpenApiParserTable();
        OpenApiParserModel.OperationContext operation = op("GET", "/users");
        onEdt(() -> parserTable.setOperations(List.of(operation)));

        AtomicReference<OpenApiParserTable.RowAction> actionRef = new AtomicReference<>();
        AtomicReference<OpenApiParserModel.OperationContext> operationRef = new AtomicReference<>();
        onEdt(() -> parserTable.setRowActionListener((action, selectedOperation) -> {
            actionRef.set(action);
            operationRef.set(selectedOperation);
        }));

        JTable jTable = tableOf(parserTable);
        onEdt(() -> {
            jTable.editCellAt(0, 7);
            TableCellEditor editor = jTable.getCellEditor();
            assertNotNull(editor);
            assertTrue(jTable.getEditorComponent() instanceof JButton);
            ((JButton) jTable.getEditorComponent()).doClick();
        });

        assertEquals(OpenApiParserTable.RowAction.COPY_AS_CURL, actionRef.get());
        assertEquals("/users", operationRef.get().path());
    }

    @Test
    void selectionActionUsesPopupSnapshotWhenPresent() throws Exception
    {
        OpenApiParserTable parserTable = new OpenApiParserTable();
        List<OpenApiParserModel.OperationContext> operations = List.of(op("GET", "/a"), op("POST", "/b"));
        onEdt(() -> parserTable.setOperations(operations));

        List<OpenApiParserModel.OperationContext> received = new ArrayList<>();
        AtomicReference<OpenApiParserTable.SelectionAction> actionRef = new AtomicReference<>();
        onEdt(() -> parserTable.setSelectionActionListener((action, selected) -> {
            actionRef.set(action);
            received.clear();
            received.addAll(selected);
        }));

        Field popupSelectionSnapshot = OpenApiParserTable.class.getDeclaredField("popupSelectionSnapshot");
        popupSelectionSnapshot.setAccessible(true);
        popupSelectionSnapshot.set(parserTable, List.of(operations.get(1)));

        Method fireSelectionAction = OpenApiParserTable.class
                .getDeclaredMethod("fireSelectionAction", OpenApiParserTable.SelectionAction.class);
        fireSelectionAction.setAccessible(true);

        onEdt(() -> {
            try
            {
                fireSelectionAction.invoke(parserTable, OpenApiParserTable.SelectionAction.SEND_SELECTED_TO_REPEATER);
            }
            catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
        });

        assertEquals(OpenApiParserTable.SelectionAction.SEND_SELECTED_TO_REPEATER, actionRef.get());
        assertEquals(1, received.size());
        assertEquals("/b", received.get(0).path());
    }

    @Test
    void selectAllClearSelectionAndVisibleActionsProvideEmptyPayload() throws Exception
    {
        OpenApiParserTable parserTable = new OpenApiParserTable();
        onEdt(() -> parserTable.setOperations(List.of(op("GET", "/a"))));

        AtomicReference<Integer> payloadSize = new AtomicReference<>(-1);
        onEdt(() -> parserTable.setSelectionActionListener((action, selected) -> payloadSize.set(selected.size())));

        Method fireSelectionAction = OpenApiParserTable.class
                .getDeclaredMethod("fireSelectionAction", OpenApiParserTable.SelectionAction.class);
        fireSelectionAction.setAccessible(true);

        onEdt(() -> invokeUnchecked(fireSelectionAction, parserTable, OpenApiParserTable.SelectionAction.SELECT_ALL));
        assertEquals(0, payloadSize.get());

        onEdt(() -> invokeUnchecked(fireSelectionAction, parserTable, OpenApiParserTable.SelectionAction.CLEAR_SELECTION));
        assertEquals(0, payloadSize.get());

        onEdt(() -> invokeUnchecked(fireSelectionAction, parserTable, OpenApiParserTable.SelectionAction.SEND_VISIBLE_TO_REPEATER));
        assertEquals(0, payloadSize.get());
    }

    private JTable tableOf(OpenApiParserTable table) throws Exception
    {
        Field field = OpenApiParserTable.class.getDeclaredField("table");
        field.setAccessible(true);
        return (JTable) field.get(table);
    }

    private OpenApiParserModel.OperationContext op(String method, String path)
    {
        return new OpenApiParserModel.OperationContext(
                "source:test",
                "test-source",
                "https://api.example/openapi.json",
                method,
                path,
                method + " " + path,
                method.toLowerCase() + "_" + path.replace('/', '_'),
                List.of("table"),
                List.of("https://api.example"),
                List.of(),
                null,
                new io.swagger.v3.oas.models.Operation()
        );
    }

    private void onEdt(Runnable runnable) throws Exception
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            runnable.run();
            return;
        }
        SwingUtilities.invokeAndWait(runnable);
    }

    private void invokeUnchecked(Method method, Object target, Object arg)
    {
        try
        {
            method.invoke(target, arg);
        }
        catch (Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }

    private void invokeSetColumnVisible(Method method, Object target, int modelIndex, boolean visible)
    {
        try
        {
            method.invoke(target, modelIndex, visible);
        }
        catch (Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }
}
