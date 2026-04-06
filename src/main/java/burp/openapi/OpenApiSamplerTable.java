package burp.openapi;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.ListSelectionModel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Table panel with OpenAPI operations and row-level actions.
 */
public final class OpenApiSamplerTable extends JPanel
{
    public enum RowAction
    {
        COPY_AS_CURL,
        COPY_AS_PYTHON
    }

    public enum SelectionAction
    {
        SEND_VISIBLE_TO_REPEATER,
        SEND_SELECTED_TO_REPEATER,
        SEND_SELECTED_TO_INTRUDER,
        SEND_SELECTED_TO_ACTIVE_SCAN,
        SEND_SELECTED_TO_PASSIVE_SCAN,
        COPY_SELECTED_AS_CURL,
        COPY_SELECTED_AS_PYTHON,
        EXPORT_SELECTED_REQUESTS,
        DELETE_SELECTED,
        SELECT_ALL,
        CLEAR_SELECTION
    }

    @FunctionalInterface
    public interface RowActionListener
    {
        void onAction(RowAction action, OpenApiSamplerModel.OperationContext operationContext);
    }

    @FunctionalInterface
    public interface SelectionActionListener
    {
        void onAction(SelectionAction action, List<OpenApiSamplerModel.OperationContext> selectedOperations);
    }

    @FunctionalInterface
    public interface SelectionChangedListener
    {
        void onSelectionChanged(OpenApiSamplerModel.OperationContext selectedOperation);
    }

    private static final int COL_METHOD = 0;
    private static final int COL_SERVER = 1;
    private static final int COL_PATH = 2;
    private static final int COL_SUMMARY = 3;
    private static final int COL_PARAMS = 4;
    private static final int COL_BODY = 5;
    private static final int COL_TAGS = 6;
    private static final int COL_CURL = 7;
    private static final int COL_PYTHON = 8;

    private final JTable table;
    private final OperationsTableModel model;
    private final TableColumn[] allColumns;
    private RowActionListener listener;
    private SelectionActionListener selectionActionListener;
    private SelectionChangedListener selectionChangedListener;
    private List<OpenApiSamplerModel.OperationContext> popupSelectionSnapshot = List.of();
    private OpenApiSamplerModel.OperationContext popupAnchorOperation;

    public OpenApiSamplerTable()
    {
        super(new BorderLayout());

        this.model = new OperationsTableModel();
        this.table = new JTable(model);
        this.table.setRowHeight(28);
        this.table.setAutoCreateRowSorter(true);
        this.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        this.table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting())
            {
                return;
            }
            fireSelectionChanged();
        });

        this.table.getColumnModel().getColumn(COL_METHOD).setCellRenderer(new MethodCellRenderer());
        this.table.getColumnModel().getColumn(COL_METHOD).setPreferredWidth(78);
        this.table.getColumnModel().getColumn(COL_SERVER).setPreferredWidth(220);
        this.table.getColumnModel().getColumn(COL_PATH).setPreferredWidth(280);
        this.table.getColumnModel().getColumn(COL_SUMMARY).setPreferredWidth(240);
        this.table.getColumnModel().getColumn(COL_PARAMS).setPreferredWidth(260);
        this.table.getColumnModel().getColumn(COL_BODY).setPreferredWidth(220);
        this.table.getColumnModel().getColumn(COL_TAGS).setPreferredWidth(170);

        installActionColumn(COL_CURL, "Copy cURL", RowAction.COPY_AS_CURL);
        installActionColumn(COL_PYTHON, "Copy Python", RowAction.COPY_AS_PYTHON);
        this.allColumns = captureColumnsByModelIndex();
        installHeaderContextMenu();
        installContextMenu();

        add(new javax.swing.JScrollPane(table), BorderLayout.CENTER);
    }

    public void setRowActionListener(RowActionListener listener)
    {
        this.listener = listener;
    }

    public void setSelectionActionListener(SelectionActionListener selectionActionListener)
    {
        this.selectionActionListener = selectionActionListener;
    }

    public void setSelectionChangedListener(SelectionChangedListener selectionChangedListener)
    {
        this.selectionChangedListener = selectionChangedListener;
    }

    public void setOperations(List<OpenApiSamplerModel.OperationContext> operations)
    {
        model.setData(operations);
    }

    public void dispose()
    {
        listener = null;
        selectionActionListener = null;
        selectionChangedListener = null;
        popupSelectionSnapshot = List.of();
        popupAnchorOperation = null;
        table.clearSelection();
        model.setData(List.of());
    }

    public List<OpenApiSamplerModel.OperationContext> visibleOperations()
    {
        return model.data();
    }

    public List<OpenApiSamplerModel.OperationContext> selectedOperations()
    {
        int[] selectedRows = table.getSelectedRows();
        if (selectedRows == null || selectedRows.length == 0)
        {
            return List.of();
        }

        List<OpenApiSamplerModel.OperationContext> selected = new ArrayList<>();
        for (int selectedRow : selectedRows)
        {
            int modelRow = table.convertRowIndexToModel(selectedRow);
            OpenApiSamplerModel.OperationContext operation = model.getAt(modelRow);
            if (operation != null)
            {
                selected.add(operation);
            }
        }
        return selected;
    }

    public void clearSelection()
    {
        table.clearSelection();
    }

    public void selectAllRows()
    {
        if (table.getRowCount() > 0)
        {
            table.selectAll();
        }
    }

    public OpenApiSamplerModel.OperationContext firstSelectedOperation()
    {
        int selectedRow = table.getSelectedRow();
        if (selectedRow < 0)
        {
            return null;
        }

        int modelRow = table.convertRowIndexToModel(selectedRow);
        return model.getAt(modelRow);
    }

    private void installActionColumn(int index, String caption, RowAction action)
    {
        final TableColumn column = table.getColumnModel().getColumn(index);
        ActionButtonCell cell = new ActionButtonCell(table, caption, action);
        column.setCellRenderer(cell);
        column.setCellEditor(cell);
        column.setPreferredWidth(114);
        column.setMinWidth(94);
    }

    private TableColumn[] captureColumnsByModelIndex()
    {
        TableColumn[] columns = new TableColumn[model.getColumnCount()];
        TableColumnModel columnModel = table.getColumnModel();
        for (int viewIndex = 0; viewIndex < columnModel.getColumnCount(); viewIndex++)
        {
            TableColumn column = columnModel.getColumn(viewIndex);
            int modelIndex = column.getModelIndex();
            if (modelIndex >= 0 && modelIndex < columns.length)
            {
                columns[modelIndex] = column;
            }
        }
        return columns;
    }

    private void installHeaderContextMenu()
    {
        table.getTableHeader().addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                maybeShowHeaderPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                maybeShowHeaderPopup(e);
            }

            private void maybeShowHeaderPopup(MouseEvent e)
            {
                if (!e.isPopupTrigger())
                {
                    return;
                }

                buildHeaderPopupMenu().show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private JPopupMenu buildHeaderPopupMenu()
    {
        JPopupMenu menu = new JPopupMenu();
        int visibleCount = table.getColumnModel().getColumnCount();
        for (int modelIndex = 0; modelIndex < model.getColumnCount(); modelIndex++)
        {
            final int columnIndex = modelIndex;
            boolean visible = isColumnVisible(columnIndex);
            String title = model.getColumnName(columnIndex);
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(title, visible);
            if (visible && visibleCount <= 1)
            {
                item.setEnabled(false);
            }
            item.addActionListener(e -> {
                boolean shouldBeVisible = item.isSelected();
                setColumnVisible(columnIndex, shouldBeVisible);
            });
            menu.add(item);
        }
        return menu;
    }

    private boolean isColumnVisible(int modelIndex)
    {
        return table.convertColumnIndexToView(modelIndex) >= 0;
    }

    private void setColumnVisible(int modelIndex, boolean visible)
    {
        if (modelIndex < 0 || modelIndex >= allColumns.length)
        {
            return;
        }

        TableColumnModel columnModel = table.getColumnModel();
        int viewIndex = table.convertColumnIndexToView(modelIndex);
        if (visible)
        {
            if (viewIndex >= 0)
            {
                return;
            }
            TableColumn column = allColumns[modelIndex];
            if (column == null)
            {
                return;
            }
            columnModel.addColumn(column);
            moveColumnToCanonicalPosition(modelIndex);
            return;
        }

        if (viewIndex < 0 || columnModel.getColumnCount() <= 1)
        {
            return;
        }

        TableColumn column = columnModel.getColumn(viewIndex);
        columnModel.removeColumn(column);
    }

    private void moveColumnToCanonicalPosition(int modelIndex)
    {
        TableColumnModel columnModel = table.getColumnModel();
        int currentViewIndex = table.convertColumnIndexToView(modelIndex);
        if (currentViewIndex < 0)
        {
            return;
        }

        int targetViewIndex = 0;
        for (int index = 0; index < modelIndex; index++)
        {
            if (table.convertColumnIndexToView(index) >= 0)
            {
                targetViewIndex++;
            }
        }

        if (currentViewIndex != targetViewIndex)
        {
            columnModel.moveColumn(currentViewIndex, targetViewIndex);
        }
    }

    private void installContextMenu()
    {
        table.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e)
            {
                if (!e.isPopupTrigger())
                {
                    return;
                }

                int row = table.rowAtPoint(e.getPoint());
                OpenApiSamplerModel.OperationContext anchor = null;
                if (row >= 0 && !table.isRowSelected(row))
                {
                    table.getSelectionModel().setSelectionInterval(row, row);
                }
                if (row >= 0)
                {
                    int modelRow = table.convertRowIndexToModel(row);
                    anchor = model.getAt(modelRow);
                }

                popupAnchorOperation = anchor;
                popupSelectionSnapshot = selectedOperations();
                if ((popupSelectionSnapshot == null || popupSelectionSnapshot.isEmpty()) && anchor != null)
                {
                    popupSelectionSnapshot = List.of(anchor);
                }
                buildPopupMenu().show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private JPopupMenu buildPopupMenu()
    {
        List<OpenApiSamplerModel.OperationContext> selected = popupSelectionSnapshot;
        boolean hasSelection = !selected.isEmpty() || popupAnchorOperation != null;

        JPopupMenu menu = new JPopupMenu();
        addPopupItem(menu, "Send all visible to Repeater", SelectionAction.SEND_VISIBLE_TO_REPEATER, table.getRowCount() > 0);
        menu.addSeparator();
        addPopupItem(menu, "Send selected to Repeater", SelectionAction.SEND_SELECTED_TO_REPEATER, hasSelection);
        addPopupItem(menu, "Send selected to Intruder", SelectionAction.SEND_SELECTED_TO_INTRUDER, hasSelection);
        addPopupItem(menu, "Send selected to Active scan", SelectionAction.SEND_SELECTED_TO_ACTIVE_SCAN, hasSelection);
        addPopupItem(menu, "Send selected to Passive scan", SelectionAction.SEND_SELECTED_TO_PASSIVE_SCAN, hasSelection);
        menu.addSeparator();
        addPopupItem(menu, "Copy selected as cURL", SelectionAction.COPY_SELECTED_AS_CURL, hasSelection);
        addPopupItem(menu, "Copy selected as Python-Requests", SelectionAction.COPY_SELECTED_AS_PYTHON, hasSelection);
        addPopupItem(menu, "Export selected requests...", SelectionAction.EXPORT_SELECTED_REQUESTS, hasSelection);
        menu.addSeparator();
        addPopupItem(menu, "Delete selected rows", SelectionAction.DELETE_SELECTED, hasSelection);
        menu.addSeparator();
        addPopupItem(menu, "Select all rows", SelectionAction.SELECT_ALL, table.getRowCount() > 0);
        addPopupItem(menu, "Clear selection", SelectionAction.CLEAR_SELECTION, hasSelection);
        return menu;
    }

    private void addPopupItem(JPopupMenu menu, String title, SelectionAction action, boolean enabled)
    {
        JMenuItem item = new JMenuItem(title);
        item.setEnabled(enabled);
        item.addActionListener(e -> fireSelectionAction(action));
        menu.add(item);
    }

    private void fireSelectionAction(SelectionAction action)
    {
        if (selectionActionListener == null)
        {
            return;
        }

        List<OpenApiSamplerModel.OperationContext> payload;
        if (action == SelectionAction.SELECT_ALL
                || action == SelectionAction.CLEAR_SELECTION
                || action == SelectionAction.SEND_VISIBLE_TO_REPEATER)
        {
            payload = List.of();
        }
        else if (popupSelectionSnapshot != null && !popupSelectionSnapshot.isEmpty())
        {
            payload = List.copyOf(popupSelectionSnapshot);
        }
        else if (popupAnchorOperation != null)
        {
            payload = List.of(popupAnchorOperation);
        }
        else
        {
            payload = selectedOperations();
        }

        selectionActionListener.onAction(action, payload);
        popupSelectionSnapshot = Collections.emptyList();
        popupAnchorOperation = null;
    }

    private void fireSelectionChanged()
    {
        if (selectionChangedListener == null)
        {
            return;
        }

        selectionChangedListener.onSelectionChanged(firstSelectedOperation());
    }

    private final class ActionButtonCell extends AbstractCellEditor implements TableCellRenderer, TableCellEditor, ActionListener
    {
        private final JTable owner;
        private final JButton button;
        private final RowAction action;
        private int editingRow = -1;

        private ActionButtonCell(JTable owner, String caption, RowAction action)
        {
            this.owner = owner;
            this.action = action;
            this.button = new JButton(caption);
            this.button.addActionListener(this);
            this.button.setFocusPainted(false);
        }

        @Override
        public Object getCellEditorValue()
        {
            return button.getText();
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column)
        {
            return button;
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table,
                Object value,
                boolean isSelected,
                int row,
                int column)
        {
            this.editingRow = row;
            return button;
        }

        @Override
        public void actionPerformed(ActionEvent e)
        {
            fireEditingStopped();

            if (listener == null || editingRow < 0)
            {
                return;
            }

            int modelRow = owner.convertRowIndexToModel(editingRow);
            OpenApiSamplerModel.OperationContext operation = model.getAt(modelRow);
            if (operation != null)
            {
                listener.onAction(action, operation);
            }
        }
    }

    private static final class MethodCellRenderer extends DefaultTableCellRenderer
    {
        private static final Color GET_BG = new Color(97, 175, 254);
        private static final Color POST_BG = new Color(73, 204, 144);
        private static final Color PUT_BG = new Color(252, 161, 48);
        private static final Color PATCH_BG = new Color(80, 227, 194);
        private static final Color DELETE_BG = new Color(249, 62, 62);
        private static final Color HEAD_BG = new Color(144, 87, 255);
        private static final Color OPTIONS_BG = new Color(144, 87, 255);
        private static final Color DEFAULT_BG = new Color(180, 180, 180);

        private MethodCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(getFont().deriveFont(Font.BOLD));
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column)
        {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (isSelected)
            {
                return c;
            }

            String method = String.valueOf(value).toUpperCase();
            Color bg = switch (method)
            {
                case "GET" -> GET_BG;
                case "POST" -> POST_BG;
                case "PUT" -> PUT_BG;
                case "PATCH" -> PATCH_BG;
                case "DELETE" -> DELETE_BG;
                case "HEAD" -> HEAD_BG;
                case "OPTIONS" -> OPTIONS_BG;
                default -> DEFAULT_BG;
            };

            c.setBackground(bg);
            c.setForeground(Color.WHITE);
            return c;
        }
    }

    private static final class OperationsTableModel extends AbstractTableModel
    {
        private static final String[] COLUMNS = {
                "Method",
                "Server",
                "Path",
                "Summary / OperationId",
                "Parameters",
                "Request body",
                "Tags",
                "Copy as curl",
                "Copy as Python"
        };

        private final List<OpenApiSamplerModel.OperationContext> data = new ArrayList<>();

        @Override
        public int getRowCount()
        {
            return data.size();
        }

        @Override
        public int getColumnCount()
        {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column)
        {
            return COLUMNS[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex)
        {
            return columnIndex >= COL_CURL;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex)
        {
            OpenApiSamplerModel.OperationContext row = data.get(rowIndex);

            return switch (columnIndex)
            {
                case COL_METHOD -> row.method();
                case COL_PATH -> row.path();
                case COL_SUMMARY -> row.summary();
                case COL_PARAMS -> row.parametersSummary();
                case COL_BODY -> row.requestBodySummary();
                case COL_TAGS -> row.tagsAsString();
                case COL_SERVER -> Utils.shortServer(row.serversAsString());
                case COL_CURL -> "Copy cURL";
                case COL_PYTHON -> "Copy Python";
                default -> "";
            };
        }

        public void setData(List<OpenApiSamplerModel.OperationContext> rows)
        {
            data.clear();
            if (rows != null)
            {
                data.addAll(rows.stream().filter(Objects::nonNull).toList());
            }
            fireTableDataChanged();
        }

        public List<OpenApiSamplerModel.OperationContext> data()
        {
            return List.copyOf(data);
        }

        public OpenApiSamplerModel.OperationContext getAt(int index)
        {
            if (index < 0 || index >= data.size())
            {
                return null;
            }
            return data.get(index);
        }
    }
}
