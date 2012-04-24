package org.esa.beam.visat.toolviews.stat;

import java.awt.BorderLayout;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Enumeration;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableColumnModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;
import org.esa.beam.framework.ui.application.ToolView;
import org.esa.beam.framework.ui.tool.ToolButtonFactory;
import sun.swing.table.DefaultTableCellHeaderRenderer;

public class TableViewPagePanel extends PagePanel {

    private AbstractButton switchToChartButton;
    private JTable table;
    private final Icon iconForSwitchToChartButton;
    private TableCellRenderer headerRenderer;

    public TableViewPagePanel(ToolView toolView, String helpId, String titlePrefix, Icon iconForSwitchToChartButton) {
        super(toolView, helpId, titlePrefix);
        this.iconForSwitchToChartButton = iconForSwitchToChartButton;
    }

    @Override
    protected void initComponents() {
        switchToChartButton = ToolButtonFactory.createButton(iconForSwitchToChartButton, false);
        switchToChartButton.setToolTipText("Switch to Chart View");
        switchToChartButton.setName("switchToChartButton");
        switchToChartButton.setEnabled(hasAlternativeView());
        switchToChartButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {

                showAlternativeView();

            }
        });

        final JPanel buttonPanel = new JPanel(new BorderLayout());
        buttonPanel.add(switchToChartButton, BorderLayout.NORTH);
        buttonPanel.add(getHelpButton(), BorderLayout.SOUTH);

        add(buttonPanel, BorderLayout.EAST);

        headerRenderer = new DefaultTableCellHeaderRenderer();

        table = new JTable(new DefaultTableModel());
        table.setColumnModel(new DefaultTableColumnModel());
        table.removeEditor();
        table.addMouseListener(new PagePanel.PopupHandler());

        final JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(table, BorderLayout.CENTER);

        final JScrollPane scrollPane = new JScrollPane(tablePanel);
        scrollPane.setColumnHeaderView(table.getTableHeader());

        add(scrollPane, BorderLayout.CENTER);

    }

    @Override
    protected void updateComponents() {
    }

    @Override
    protected String getDataAsText() {
        final StringWriter writer = new StringWriter();
        try {
            new TableModelCsvEncoder(table.getModel()).encodeCsv(writer);
            writer.close();
        } catch (IOException ignore) {
        }
        return writer.toString();
    }

    void setModel(TableModel tableModel) {
        table.setModel(tableModel);
        if (table.getColumnCount() > 0) {
            final Enumeration<TableColumn> columns = table.getColumnModel().getColumns();
            while (columns.hasMoreElements()) {
                TableColumn tableColumn = columns.nextElement();
                tableColumn.setHeaderRenderer(headerRenderer);
                tableColumn.sizeWidthToFit();
                tableColumn.setMaxWidth(Integer.MAX_VALUE);
            }
        }
    }
}
