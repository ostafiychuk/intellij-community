/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.internal.inspector;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.notification.NotificationsManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.roots.ui.configuration.actions.IconWithTextAction;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.StripeTable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ObjectUtils;
import com.intellij.util.ReflectionUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.WeakKeyWeakValueHashMap;
import com.intellij.util.ui.ColorIcon;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.PlatformColors;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * User: spLeaner
 */
public class UiInspectorAction extends ToggleAction implements DumbAware {

  private UiInspector myInspector = null;

  @Override
  public boolean isSelected(AnActionEvent e) {
    return myInspector != null;
  }

  @Override
  public void setSelected(AnActionEvent e, boolean state) {
    if (state) {
      if (myInspector == null) {
        myInspector = new UiInspector();
        Toolkit.getDefaultToolkit().addAWTEventListener(myInspector, AWTEvent.MOUSE_EVENT_MASK);
      }

      UiInspectorNotification[] existing =
        NotificationsManager.getNotificationsManager().getNotificationsOfType(UiInspectorNotification.class, null);
      if (existing.length == 0) {
        Notifications.Bus.notify(new UiInspectorNotification(), null);
      }
    }
    else {
      if (myInspector != null) {
        Toolkit.getDefaultToolkit().removeAWTEventListener(myInspector);
        Disposer.dispose(myInspector);
        myInspector = null;
      }
    }
  }
  
  private static class UiInspectorNotification extends Notification {
    private UiInspectorNotification() {
      super(Notifications.SYSTEM_MESSAGES_GROUP_ID, "UI Inspector", "Control-Alt-Click to view component info!",
            NotificationType.INFORMATION);
    }
  }

  private static class InspectorWindow extends JDialog {
    private InspectorTable myInspectorTable;
    private UiInspector myUiInspector;
    private Component myComponent;
    private HighlightComponent myHighlightComponent;
    private HierarchyTree myHierarchyTree;
    private final JPanel myWrapperPanel;

    private InspectorWindow(@NotNull Component component, UiInspector uiInspector) throws HeadlessException {
      super(findWindow(component));
      Window window = findWindow(component);
      setModal(window instanceof JDialog && ((JDialog)window).isModal());
      myComponent = component;
      myUiInspector = uiInspector;
      getRootPane().setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

      setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

      setLayout(new BorderLayout());
      setTitle(component.getClass().getName());

      DefaultActionGroup actions = new DefaultActionGroup();
      actions.addAction(new IconWithTextAction("Highlight") {
        @Override
        public void actionPerformed(AnActionEvent e) {
          setHighlightingEnabled(myHighlightComponent == null);
        }

        @Override
        public void update(AnActionEvent e) {
          e.getPresentation().setEnabled(myComponent != null && myComponent.isVisible());
        }

      });

      actions.addSeparator();

      actions.add(new IconWithTextAction("Refresh") {

        @Override
        public void actionPerformed(AnActionEvent e) {
          getCurrentTable().refresh();
        }

        @Override
        public void update(AnActionEvent e) {
          e.getPresentation().setEnabled(myComponent != null && myComponent.isVisible());
        }
      });

      ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.CONTEXT_TOOLBAR, actions, true);
      add(toolbar.getComponent(), BorderLayout.NORTH);

      myWrapperPanel = new JPanel(new BorderLayout());

      myInspectorTable = new InspectorTable(component);
      myHierarchyTree = new HierarchyTree(component) {
        @Override
        public void onComponentChanged(Component c) {
          boolean wasHighlighted = myHighlightComponent != null;
          setHighlightingEnabled(false);
          switchInfo(c);
          setHighlightingEnabled(wasHighlighted);
        }
      };
      
      myWrapperPanel.add(myInspectorTable, BorderLayout.CENTER);
      
      JSplitPane splitPane = new JSplitPane();
      splitPane.setDividerLocation(0.5);
      splitPane.setRightComponent(myWrapperPanel);

      JScrollPane pane = new JBScrollPane(myHierarchyTree);
      splitPane.setLeftComponent(pane);
      add(splitPane, BorderLayout.CENTER);

      myHierarchyTree.expandPath();

      addWindowListener(new WindowAdapter() {
        @Override
        public void windowClosing(WindowEvent e) {
          close();
        }
      });

      getRootPane().getActionMap().put("CLOSE", new AbstractAction() {
        public void actionPerformed(ActionEvent e) {
          close();
        }
      });
      setHighlightingEnabled(true);
      getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "CLOSE");
    }

    private static Window findWindow(Component component) {
      DialogWrapper dialogWrapper = DialogWrapper.findInstance(component);
      if (dialogWrapper != null) {
        return dialogWrapper.getPeer().getWindow();
      }
      return null;
    }

    private InspectorTable getCurrentTable() {
      return myInspectorTable;
    }
    
    private void switchInfo(@Nullable Component c) {
      if (c == null) return;
      myComponent = c;
      setTitle(myComponent.getClass().getName());
      myWrapperPanel.removeAll();
      myInspectorTable = new InspectorTable(c);
      myWrapperPanel.add(myInspectorTable, BorderLayout.CENTER);
      myWrapperPanel.revalidate();
      myWrapperPanel.repaint();
    }
    
    public void close() {
      setHighlightingEnabled(false);
      myComponent = null;
      setVisible(false);
      dispose();
    }

    private void setHighlightingEnabled(boolean enable) {
      Component target = enable ? myComponent : myHighlightComponent;
      JRootPane rootPane = target == null ? null : SwingUtilities.getRootPane(target);
      JComponent glassPane = rootPane == null ? null : (JComponent)rootPane.getGlassPane();
      if (glassPane == null) {
        myHighlightComponent = null;
        return;
      }
      if (enable) {
        myHighlightComponent = new HighlightComponent(JBColor.GREEN);

        Point pt = SwingUtilities.convertPoint(myComponent, new Point(0, 0), rootPane);
        myHighlightComponent.setBounds(pt.x, pt.y, myComponent.getWidth(), myComponent.getHeight());
        glassPane.add(myHighlightComponent);
      }
      else {
        glassPane.remove(myHighlightComponent);
        myHighlightComponent = null;
      }
      glassPane.revalidate();
      glassPane.repaint();
    }

  }
  
  private static class ComponentTreeCellRenderer extends JLabel implements TreeCellRenderer {
    private ComponentTreeCellRenderer() {
      setOpaque(true);
      setFont(JBUI.Fonts.label(11));
      setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree,
                                                  Object value,
                                                  boolean selected,
                                                  boolean expanded,
                                                  boolean leaf,
                                                  int row,
                                                  boolean hasFocus) {
      Color foreground = selected ? UIUtil.getTreeSelectionForeground() : UIUtil.getTreeForeground();
      Color background = selected ? UIUtil.getTreeSelectionBackground() : null; 
      if (value instanceof HierarchyTree.ComponentNode) {
        HierarchyTree.ComponentNode componentNode = (HierarchyTree.ComponentNode)value;
        Component component = componentNode.getOwnComponent();
        String name = component.getName();
        if (StringUtil.isEmpty(name)) {
          name = component.getClass().getSimpleName();
          if (name.isEmpty()) {
            name = component.getClass().getSuperclass().getSimpleName();
          }
        }

        if (!selected) {
          if (!component.isVisible()) {
            foreground = JBColor.GRAY;
          }
          else if (component.getWidth() == 0 || component.getHeight() == 0) {
            foreground = new Color(128, 10, 0);
          }
          else if (component.getPreferredSize() != null &&
                   (component.getSize().width < component.getPreferredSize().width
                    || component.getSize().height < component.getPreferredSize().height)) {
            foreground = PlatformColors.BLUE;
          }

          if (componentNode.getToSelect() == componentNode.getOwnComponent()) {
            background = new Color(31, 128, 8, 58);
          }
        }
        setText(name);
      }
      
      setForeground(foreground);
      setBackground(background);
      
      return this;
    }
  }

  private abstract static class HierarchyTree extends JTree implements TreeSelectionListener {
    final Component myComponent;

    private HierarchyTree(Component c) {
      myComponent = c;
      setModel(buildModel(c));
      setCellRenderer(new ComponentTreeCellRenderer());
      getSelectionModel().addTreeSelectionListener(this);
    }
    
    public void expandPath() {
      TreeUtil.expandAll(this);
      int count = getRowCount();
      ComponentNode node = new ComponentNode(myComponent, myComponent.getParent());

      for (int i = 0; i < count; i++) {
        TreePath row = getPathForRow(i);
        if (row.getLastPathComponent().equals(node)) {
          setSelectionPath(row);
          scrollPathToVisible(getSelectionPath());
          break;
        }
      }
    }
    
    @Override
    public void valueChanged(TreeSelectionEvent e) {
      TreePath path = e.getNewLeadSelectionPath();
      if (path == null) {
        onComponentChanged(null);
        return;
      }
      Object component = path.getLastPathComponent();
      if (component instanceof ComponentNode) {
        Component c = ((ComponentNode)component).getOwnComponent();
        onComponentChanged(c);
      }
    }

    private static TreeModel buildModel(Component c) {
      return new DefaultTreeModel(new ComponentNode(c, null));
    }
    
    public abstract void onComponentChanged(Component c);
    
    private static class ComponentNode extends DefaultMutableTreeNode  {
      private final Component myParent;
      private final Component myToSelect;

      private ComponentNode(Object userObject, Component parent) {
        super(userObject);
        myParent = parent == null ? SwingUtilities.getWindowAncestor((Component) userObject) : parent;
        myToSelect = (Component)userObject;
        children = prepareChildren(myToSelect, parent);
      }
      
      public Component getOwnComponent() {
        return myParent;
      }
      
      public Component getToSelect() {
        return myToSelect; 
      }

      @Override
      public String toString() {
        return myParent.getClass().getName();
      }

      @Override
      public boolean equals(Object obj) {
        return obj instanceof ComponentNode && ((ComponentNode)obj).getOwnComponent() == getOwnComponent();
      }

      @SuppressWarnings("UseOfObsoleteCollectionType")
      private static Vector prepareChildren(Component toSelect, Component parent) {
        Vector<ComponentNode> result = new Vector<ComponentNode>();
        if (parent == null) {
          Container root = SwingUtilities.windowForComponent(toSelect);
          for (Component component : root.getComponents()) {
            result.add(new ComponentNode(toSelect, component));
          }
        } else {
          if (parent instanceof Container) {
            for (Component component : ((Container)parent).getComponents()) {
              result.add(new ComponentNode(toSelect, component));
            }
          }
        }
        
        return result;
      }
    }
  }
  
  private static class HighlightComponent extends JComponent {
    Color myColor;

    private HighlightComponent(@NotNull final Color c) {
      myColor = c;
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2d = (Graphics2D)g;

      Color oldColor = g2d.getColor();
      g2d.setColor(myColor);
      Composite old = g2d.getComposite();
      g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.2f));

      Rectangle r = getBounds();

      g2d.fillRect(0, 0, r.width, r.height);

      g2d.setColor(myColor.darker());
      g2d.drawRect(0, 0, r.width - 1, r.height - 1);

      g2d.setComposite(old);
      g2d.setColor(oldColor);
    }
  }

  private static class InspectorTable extends JPanel {
    InspectorTableModel myModel;
    DimensionsComponent myDimensionComponent;

    private InspectorTable(@NotNull final Component component) {
      setLayout(new BorderLayout());

      myModel = new InspectorTableModel(component);
      final StripeTable table = new StripeTable(myModel);

      TableColumnModel columnModel = table.getColumnModel();
      TableColumn propertyColumn = columnModel.getColumn(0);
      propertyColumn.setMinWidth(150);
      propertyColumn.setMaxWidth(150);
      propertyColumn.setResizable(false);

      TableColumn valueColumn = columnModel.getColumn(1);
      valueColumn.setMinWidth(200);
      valueColumn.setResizable(false);
      valueColumn.setCellRenderer(new ValueCellRenderer());

      table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

      add(new JBScrollPane(table), BorderLayout.CENTER);
      myDimensionComponent = new DimensionsComponent(component);
      add(myDimensionComponent, BorderLayout.SOUTH);
    }

    public void refresh() {
      myModel.refresh();
      myDimensionComponent.update();
      myDimensionComponent.repaint();
    }
  }

  private static class DimensionsComponent extends JComponent {
    Component myComponent;
    int myWidth;
    int myHeight;
    Border myBorder;
    Insets myInsets;

    private DimensionsComponent(@NotNull final Component component) {
      myComponent = component;
      setOpaque(true);
      setBackground(JBColor.WHITE);
      setBorder(new EmptyBorder(5, 0, 5, 0));

      setFont(new JLabel().getFont().deriveFont(Font.PLAIN, 9));

      update();
    }

    public void update() {
      myWidth = myComponent.getWidth();
      myHeight = myComponent.getHeight();
      if (myComponent instanceof JComponent) {
        myBorder = ((JComponent)myComponent).getBorder();
        myInsets = ((JComponent)myComponent).getInsets();
      }
    }

    @Override
    protected void paintComponent(final Graphics g) {
      Graphics2D g2d = (Graphics2D)g;
      Rectangle bounds = getBounds();

      g2d.setColor(getBackground());
      Insets insets = getInsets();
      g2d.fillRect(insets.left, insets.top, bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom);
      g2d.setColor(getForeground());

      final String sizeString = String.valueOf(myWidth) + " x " + myHeight;

      FontMetrics fm = g2d.getFontMetrics();
      int sizeWidth = fm.stringWidth(sizeString);

      int fontHeight = fm.getHeight();

      g2d.drawString(sizeString, bounds.width / 2 - sizeWidth / 2, bounds.height / 2 + fontHeight / 2);

      g2d.setColor(JBColor.GRAY);

      int innerX = bounds.width / 2 - sizeWidth / 2 - 20;
      int innerY = bounds.height / 2 - fontHeight / 2 - 5;
      int innerWidth = sizeWidth + 40;
      int innerHeight = fontHeight + 10;

      g2d.drawRect(innerX, innerY, innerWidth, innerHeight);

      Insets borderInsets = null;
      if (myBorder != null) borderInsets = myBorder.getBorderInsets(myComponent);
      UIUtil.drawDottedRectangle(g2d, innerX - 15, innerY - 15, innerX - 15 + innerWidth + 30, innerY - 15 + innerHeight + 30);
      drawInsets(g2d, fm, "border", borderInsets, 15, fontHeight, innerX, innerY, innerWidth, innerHeight);

      g2d.drawRect(innerX - 30, innerY - 30, innerWidth + 60, innerHeight + 60);
      drawInsets(g2d, fm, "insets", myInsets, 30, fontHeight, innerX, innerY, innerWidth, innerHeight);
    }

    private static void drawInsets(Graphics2D g2d, FontMetrics fm, String name, Insets insets, int offset, int fontHeight, int innerX, int innerY, int innerWidth, int innerHeight) {
      g2d.setColor(JBColor.BLACK);
      g2d.drawString(name, innerX - offset + 5, innerY - offset + fontHeight);

      g2d.setColor(JBColor.GRAY);
      int dashWidth = fm.stringWidth("-");

      if (insets != null) {
        final String top = Integer.toString(insets.top);
        final String bottom = Integer.toString(insets.bottom);
        final String left = Integer.toString(insets.left);
        final String right = Integer.toString(insets.right);

        g2d.drawString(top, innerX - offset + ((innerWidth + offset * 2) / 2 - fm.stringWidth(top) / 2), innerY - offset + fontHeight);
        g2d.drawString(bottom, innerX - offset + ((innerWidth + offset * 2) / 2 - fm.stringWidth(bottom) / 2), innerY - offset  + innerHeight + offset*2 - 8 + fontHeight / 2);
        g2d.drawString(left, innerX - offset + 7 - fm.stringWidth(left) / 2, innerY - offset + (innerHeight + offset * 2) / 2 + fontHeight / 2);
        g2d.drawString(right, innerX + innerWidth + offset - 7 - fm.stringWidth(right) / 2, innerY - offset + (innerHeight + offset * 2) / 2 + fontHeight / 2);
      } else {
        g2d.drawString("-", innerX - offset + ((innerWidth + offset * 2) / 2 - dashWidth / 2), innerY - offset + fontHeight);
        g2d.drawString("-", innerX - offset + ((innerWidth + offset * 2) / 2 - dashWidth / 2), innerY - offset  + innerHeight + offset*2 - 8 + fontHeight / 2);
        g2d.drawString("-", innerX - offset + 7 - dashWidth / 2, innerY - offset + (innerHeight + offset * 2) / 2 + fontHeight / 2);
        g2d.drawString("-", innerX + innerWidth + offset - 7 - dashWidth / 2, innerY - offset + (innerHeight + offset * 2) / 2 + fontHeight / 2);
      }
    }

    @Override
    public Dimension getMinimumSize() {
      return new Dimension(120, 120);
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(150, 150);
    }
  }

  private static class ValueCellRenderer implements TableCellRenderer {
    private static final Map<Class, Renderer> RENDERERS = ContainerUtil.newHashMap();

    static {
      RENDERERS.put(Point.class, new PointRenderer());
      RENDERERS.put(Dimension.class, new DimensionRenderer());
      RENDERERS.put(Insets.class, new InsetsRenderer());
      RENDERERS.put(Rectangle.class, new RectangleRenderer());
      RENDERERS.put(Color.class, new ColorRenderer());
      RENDERERS.put(Font.class, new FontRenderer());
      RENDERERS.put(Boolean.class, new BooleanRenderer());
      RENDERERS.put(Icon.class, new IconRenderer());
    }

    private static final Renderer<Object> DEFAULT_RENDERER = new ObjectRenderer();

    private static final JLabel NULL_RENDERER = new JLabel("-");

    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      if (value == null) {
        NULL_RENDERER.setOpaque(isSelected);
        NULL_RENDERER.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
        NULL_RENDERER.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
        return NULL_RENDERER;
      }

      Renderer<Object> renderer = ObjectUtils.notNull(getRenderer(value.getClass()), DEFAULT_RENDERER);

      JComponent result = renderer.setValue(value);
      result.setOpaque(isSelected);
      result.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
      result.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
      return result;
    }

    @Nullable
    private static Renderer<Object> getRenderer(Class clazz) {
      if (clazz == null) return null;

      Renderer<Object> renderer = (Renderer<Object>)RENDERERS.get(clazz);
      if (renderer != null) return renderer;

      Class[] interfaces = clazz.getInterfaces();
      for (Class aClass : interfaces) {
        renderer = getRenderer(aClass);
        if (renderer != null) {
          return renderer;
        }
      }
      clazz = clazz.getSuperclass();
      if (clazz != null) {
        return getRenderer(clazz);
      }
      return null;
    }
  }

  private interface Renderer<T> {
    JComponent setValue(@NotNull T value);
  }

  private static class PointRenderer extends JLabel implements Renderer<Point> {
    public JComponent setValue(@NotNull final Point value) {
      setText(String.valueOf(value.x) + ':' + value.y);
      return this;
    }
  }

  private static class DimensionRenderer extends JLabel implements Renderer<Dimension> {
    public JComponent setValue(@NotNull final Dimension value) {
      setText(String.valueOf(value.width) + " x " + value.height);
      return this;
    }
  }

  private static class InsetsRenderer extends JLabel implements Renderer<Insets> {
    public JComponent setValue(@NotNull final Insets value) {
      setText("top: " + value.top + " left:" + value.left + " bottom:" + value.bottom + " right:" + value.right);
      return this;
    }
  }

  private static class RectangleRenderer extends JLabel implements Renderer<Rectangle> {
    public JComponent setValue(@NotNull final Rectangle value) {
      setText(String.valueOf(value.x) + ":" + value.y + ", " + value.width + " x " + value.height);
      return this;
    }
  }

  private static class ColorRenderer extends JLabel implements Renderer<Color> {
    public JComponent setValue(@NotNull final Color value) {
      setText("r:" + value.getRed() + ", g:" + value.getGreen() + ", b:" + value.getBlue());
      setIcon(new ColorIcon(13, 11, value, true));
      return this;
    }
  }

  private static class FontRenderer extends JLabel implements Renderer<Font> {
    public JComponent setValue(@NotNull final Font value) {
      setText(value.getFontName() + " (" + value.getFamily() + "), " + value.getSize() + "px");
      return this;
    }
  }

  private static class BooleanRenderer extends JLabel implements Renderer<Boolean> {
    public JComponent setValue(@NotNull final Boolean value) {
      setText(value ? "Yes" : "No");
      return this;
    }
  }

  private static class IconRenderer extends JLabel implements Renderer<Icon> {
    public JComponent setValue(@NotNull final Icon value) {
      setIcon(value);
      return this;
    }
  }

  private static class ObjectRenderer extends JLabel implements Renderer<Object> {
    {
      putClientProperty("html.disable", Boolean.TRUE);
    }
    public JComponent setValue(@NotNull final Object value) {
      setText(String.valueOf(value).replace('\n', ' '));
      return this;
    }
  }

  private static class PropertyBean {
    final String propertyName;
    final Object propertyValue;

    PropertyBean(String propertyName, Object propertyValue) {
      this.propertyName = propertyName;
      this.propertyValue = propertyValue;
    }
  }

  private static class InspectorTableModel extends AbstractTableModel {

    private final List<String> PROPERTIES = Arrays.asList(
      "getClass", "ui", "getLocation", "getLocationOnScreen",
      "getSize", "isOpaque", "getBorder",
      "getForeground", "getBackground", "getFont",
      "getMinimumSize", "getMaximumSize", "getPreferredSize",
      "getAlignmentX", "getAlignmentY",
      "getText", "isEditable", "getIcon",
      "getTooltipText", "getToolTipText",
      "getVisibleRect", "getLayout",
      "isShowing", "isEnabled", "isVisible", "isFocusable",
      "isFocusCycleRoot", "isFocusOwner",
      "isValid", "isDisplayable", "isLightweight"
    );

    private Component myComponent;
    private List<PropertyBean> myProperties = new ArrayList<PropertyBean>();

    public InspectorTableModel(@NotNull final Component c) {
      myComponent = c;

      fillTable();
    }

    private void fillTable() {
      Class<? extends Component> cls = myComponent.getClass();
      for (String name: PROPERTIES) {
        String propertyName = ObjectUtils.notNull(StringUtil.getPropertyName(name), name);
        Object propertyValue;
        try {
          propertyValue = cls.getMethod(name).invoke(myComponent);
        }
        catch (Exception ignored) {
          try {
            propertyValue = ReflectionUtil.findField(cls, null, name).get(myComponent);
          }
          catch (Exception ignored2) {
            continue;
          }
        }
        myProperties.add(new PropertyBean(propertyName, propertyValue));
      }
    }

    @Nullable
    public Object getValueAt(int row, int column) {
      final PropertyBean bean = myProperties.get(row);
      if (bean != null) {
        switch (column) {
          case 0:
            return bean.propertyName;
          default:
            return bean.propertyValue;
        }
      }

      return null;
    }

    public int getColumnCount() {
      return 2;
    }

    public int getRowCount() {
      return myProperties.size();
    }

    public String getColumnName(int columnIndex) {
      return columnIndex == 0 ? "Property" : "Value";
    }

    public Class<?> getColumnClass(int columnIndex) {
      return columnIndex == 0 ? String.class : Object.class;
    }

    public void refresh() {
      myProperties.clear();
      fillTable();
      fireTableDataChanged();
    }
  }

  private static class UiInspector implements AWTEventListener, Disposable {
    Map<Component, InspectorWindow> myComponentToInspector = new WeakKeyWeakValueHashMap<Component, InspectorWindow>();

    public void dispose() {
      for (InspectorWindow w : myComponentToInspector.values()) {
        w.close();
      }
      myComponentToInspector.clear();
    }

    public void showInspector(@NotNull Component c) {
      InspectorWindow window = myComponentToInspector.get(c);
      if (window != null) {
        window.switchInfo(c);
        window.setHighlightingEnabled(true);
        window.setVisible(true);
        window.toFront();
      }
      else {
        window = new InspectorWindow(c, this);
        myComponentToInspector.put(c, window);
        window.pack();
        window.setVisible(true);
        window.toFront();
      }
    }

    public void eventDispatched(AWTEvent event) {
      if (event instanceof MouseEvent) {
        final MouseEvent me = (MouseEvent)event;
        if (me.isAltDown() && me.isControlDown()) {
          switch (me.getID()) {
            case MouseEvent.MOUSE_CLICKED:
              if (me.getClickCount() == 1 && !me.isPopupTrigger()) {
                Object source = me.getSource();
                if (source instanceof Component) {
                  showInspector((Component)source);
                }
                else {
                  Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                  if (owner != null) {
                    showInspector(owner);
                  }
                }
                me.consume();
              }

              break;
            default:
              break;
          }
        }
      }
    }
  }
}
