package project;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Swing GUI: single-seed and seed-list outpost search (Minecraft 26.2). */
public class OutpostGui extends JFrame {

    private static final int CPUS = Runtime.getRuntime().availableProcessors();
    /** Single seed default: 2M x 2M blocks (about 5 min on 12 threads). */
    private static final int SINGLE_DEFAULT_SIDE = 3908;
    /** Seed list default: 128K x 128K blocks per seed. */
    private static final int LIST_DEFAULT_SIDE = 256;
    /** Below this many regions per seed, list mode runs one seed per thread. */
    private static final long SMALL_AREA = 50_000;

    private final AppLocale loc;

    public static void main(String[] args) {
        AppLocale loc = AppLocale.load();
        SwingUtilities.invokeLater(() -> {
            OutpostGui gui = new OutpostGui(loc);
            gui.setVisible(true);
            if (!NativeOutpost.isAvailable()) {
                JOptionPane.showMessageDialog(gui, loc.get("error.native", NativeOutpost.describe()),
                        loc.get("error.title"), JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public OutpostGui(AppLocale loc) {
        super(AppVersion.titled(loc.get("window.title")));
        this.loc = loc;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        java.net.URL icon = getClass().getResource("/icon.png");
        if (icon != null) {
            setIconImage(new ImageIcon(icon).getImage());
        }
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(loc.get("tab.single"), new SearchPanel(false));
        tabs.addTab(loc.get("tab.list"), new SearchPanel(true));
        add(tabs);
        setSize(1200, 720);
        setLocationRelativeTo(null);
        System.out.println(NativeOutpost.describe());
    }

    // ------------------------------------------------------------------

    private final class SearchPanel extends JPanel {
        private final boolean listMode;
        private final int defaultSide;

        private final JTextField seedField = new JTextField(20);
        private final JLabel fileLabel = new JLabel();
        private File seedFile;
        private final JSpinner threads = new JSpinner(new SpinnerNumberModel(CPUS, 1, CPUS, 1));
        private final JSpinner maxY = new JSpinner(new SpinnerNumberModel(-40, -64, 320, 1));
        private final JCheckBox squareBox = new JCheckBox(loc.get("label.squareSide"), true);
        private final JTextField sideField = new JTextField(8);
        private final JTextField minX = new JTextField(8), maxX = new JTextField(8),
                minZ = new JTextField(8), maxZ = new JTextField(8);
        private final JLabel areaHint = new JLabel(" ");

        private final JButton start = new JButton(loc.get("button.start"));
        private final JButton pause = new JButton(loc.get("button.pause"));
        private final JButton stop = new JButton(loc.get("button.stop"));
        private final JProgressBar bar = new JProgressBar(0, 10_000);
        private final JLabel status = new JLabel(loc.get("progress.idle"));
        private final JLabel elapsedLabel = new JLabel(" ");
        private final JLabel remainingLabel = new JLabel(" ");

        private final HitModel model = new HitModel();
        private final JTable table = new JTable(model);
        private final TableRowSorter<HitModel> sorter = new TableRowSorter<>(model);
        private final TitledBorder resultBorder = BorderFactory.createTitledBorder("");

        private OutpostSearch.Control control;
        private Timer clock;
        private long activeMs, resumedAt;
        private volatile double fraction;

        SearchPanel(boolean listMode) {
            super(new BorderLayout(8, 8));
            this.listMode = listMode;
            this.defaultSide = listMode ? LIST_DEFAULT_SIDE : SINGLE_DEFAULT_SIDE;
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            add(buildForm(), BorderLayout.WEST);
            add(buildResults(), BorderLayout.CENTER);
            setSquare(defaultSide);
            updateAreaHint();
            setRunning(false);
        }

        private JComponent buildForm() {
            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            int row = 0;

            if (listMode) {
                fileLabel.setText(loc.get("label.noFile"));
                JButton pick = new JButton(loc.get("button.selectFile"));
                pick.addActionListener(e -> pickFile());
                JPanel p = new JPanel(new BorderLayout(6, 0));
                p.add(fileLabel, BorderLayout.CENTER);
                p.add(pick, BorderLayout.EAST);
                row = addRow(form, c, row, loc.get("label.seedFile"), p);
            } else {
                row = addRow(form, c, row, loc.get("label.seed"), seedField);
            }
            row = addRow(form, c, row, loc.get("label.threads"), threads);
            row = addRow(form, c, row, loc.get("label.maxY"), maxY);
            JLabel yHint = new JLabel("<html><div style='width:260px'><i>" + loc.get("label.maxYHint") + "</i></div></html>");
            c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
            form.add(yHint, c);
            c.gridwidth = 1;

            c.gridx = 0; c.gridy = row;
            form.add(squareBox, c);
            c.gridx = 1;
            form.add(sideField, c);
            row++;
            row = addRow(form, c, row, loc.get("label.minX"), minX);
            row = addRow(form, c, row, loc.get("label.maxX"), maxX);
            row = addRow(form, c, row, loc.get("label.minZ"), minZ);
            row = addRow(form, c, row, loc.get("label.maxZ"), maxZ);
            c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
            form.add(areaHint, c);

            JButton reset = new JButton(loc.get(listMode ? "button.defaultArea" : "button.world"));
            reset.addActionListener(e -> {
                if (listMode) {
                    squareBox.setSelected(true);
                    setSquare(defaultSide);
                } else {
                    squareBox.setSelected(false);
                    fillArea(OutpostSearch.WORLD_MIN, OutpostSearch.WORLD_MAX);
                }
                updateAreaHint();
            });
            c.gridy = row++;
            form.add(reset, c);

            JPanel buttons = new JPanel(new GridLayout(1, 3, 6, 0));
            buttons.add(start);
            buttons.add(pause);
            buttons.add(stop);
            c.gridy = row++;
            form.add(buttons, c);
            c.gridy = row++;
            form.add(bar, c);
            for (JLabel l : new JLabel[]{status, elapsedLabel, remainingLabel}) {
                c.gridy = row++;
                form.add(l, c);
            }
            JLabel credit = new JLabel(loc.get("credit.text"), SwingConstants.CENTER);
            c.gridy = row++;
            c.insets = new Insets(20, 4, 4, 4);
            form.add(credit, c);
            c.gridy = row;
            c.weighty = 1;
            form.add(Box.createGlue(), c);

            start.addActionListener(e -> startSearch());
            pause.addActionListener(e -> togglePause());
            stop.addActionListener(e -> {
                if (control != null) control.stopped = true;
            });
            squareBox.addActionListener(e -> {
                updateFieldState();
                if (squareBox.isSelected()) applySquare();
            });
            onChange(sideField, () -> {
                if (squareBox.isSelected()) applySquare();
            });
            for (JTextField f : new JTextField[]{minX, maxX, minZ, maxZ}) onChange(f, this::updateAreaHint);
            return form;
        }

        private int addRow(JPanel form, GridBagConstraints c, int row, String label, JComponent field) {
            c.gridwidth = 1;
            c.gridx = 0; c.gridy = row;
            form.add(new JLabel(label), c);
            c.gridx = 1;
            form.add(field, c);
            return row + 1;
        }

        private JComponent buildResults() {
            table.setRowSorter(sorter);
            table.setFillsViewportHeight(true);
            if (!listMode) {
                table.removeColumn(table.getColumnModel().getColumn(0)); // seed column
            }
            table.getColumnModel().getColumn(listMode ? 1 : 0).setPreferredWidth(220);
            table.getColumnModel().getColumn(listMode ? 2 : 1).setPreferredWidth(130);
            if (listMode) table.getColumnModel().getColumn(0).setPreferredWidth(170);
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) copySelected();
                }
            });

            JButton sortY = new JButton(loc.get("button.sortY"));
            sortY.addActionListener(e -> sorter.setSortKeys(List.of(
                    new RowSorter.SortKey(HitModel.COL_Y, SortOrder.ASCENDING))));
            JButton sortD = new JButton(loc.get("button.sortDistance"));
            sortD.addActionListener(e -> sorter.setSortKeys(List.of(
                    new RowSorter.SortKey(HitModel.COL_DIST, SortOrder.ASCENDING))));
            JButton copy = new JButton(loc.get("button.copy"));
            copy.addActionListener(e -> copySelected());
            JButton export = new JButton(loc.get("button.export"));
            export.addActionListener(e -> export(false));

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
            buttons.add(sortY);
            buttons.add(sortD);
            buttons.add(copy);
            buttons.add(export);
            if (listMode) {
                JButton exportSeeds = new JButton(loc.get("button.exportSeeds"));
                exportSeeds.addActionListener(e -> export(true));
                buttons.add(exportSeeds);
            }

            JPanel p = new JPanel(new BorderLayout());
            p.setBorder(resultBorder);
            p.add(new JLabel(loc.get("result.copyHint")), BorderLayout.NORTH);
            p.add(new JScrollPane(table), BorderLayout.CENTER);
            p.add(buttons, BorderLayout.SOUTH);
            updateResultTitle();
            return p;
        }

        // ---------------- area fields ----------------

        private void setSquare(int side) {
            sideField.setText(String.valueOf(side));
            applySquare();
            updateFieldState();
        }

        private void applySquare() {
            try {
                long side = Long.parseLong(sideField.getText().trim());
                if (side < 1 || side > 2L * OutpostSearch.WORLD_MAX + 2) return;
                long min = -side / 2;
                fillArea((int) min, (int) (min + side - 1));
            } catch (NumberFormatException ignored) {
                // wait for a valid number
            }
        }

        private void fillArea(int min, int max) {
            minX.setText(String.valueOf(min));
            maxX.setText(String.valueOf(max));
            minZ.setText(String.valueOf(min));
            maxZ.setText(String.valueOf(max));
        }

        private void updateFieldState() {
            boolean idle = control == null;
            sideField.setEnabled(idle && squareBox.isSelected());
            for (JTextField f : new JTextField[]{minX, maxX, minZ, maxZ}) {
                f.setEnabled(idle && !squareBox.isSelected());
            }
        }

        private void updateAreaHint() {
            try {
                int[] b = areaBounds();
                long w = (long) (b[1] - b[0] + 1) * 512, h = (long) (b[3] - b[2] + 1) * 512;
                areaHint.setText(loc.get("hint.blocks", String.format("%,d", w), String.format("%,d", h),
                        String.format("%,d", OutpostSearch.regionCount(b[0], b[1], b[2], b[3]))));
            } catch (IllegalArgumentException e) {
                areaHint.setText(" ");
            }
        }

        /** {minX, maxX, minZ, maxZ}; throws IllegalArgumentException with a user message. */
        private int[] areaBounds() {
            int[] b = {num(minX, "label.minX"), num(maxX, "label.maxX"), num(minZ, "label.minZ"), num(maxZ, "label.maxZ")};
            if (b[0] > b[1] || b[2] > b[3]) throw new IllegalArgumentException(loc.get("error.bounds"));
            return b;
        }

        private int num(JTextField f, String labelKey) {
            try {
                return Integer.parseInt(f.getText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(loc.get("error.number", loc.get(labelKey).replaceAll("[:：]$", "")));
            }
        }

        // ---------------- search ----------------

        private void pickFile() {
            JFileChooser fc = new JFileChooser(seedFile != null ? seedFile.getParentFile() : new File("."));
            fc.setFileFilter(new FileNameExtensionFilter(loc.get("dialog.textFiles"), "txt"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                seedFile = fc.getSelectedFile();
                fileLabel.setText(seedFile.getName());
            }
        }

        private void startSearch() {
            if (!NativeOutpost.isAvailable()) {
                error(loc.get("error.native", NativeOutpost.describe()));
                return;
            }
            int[] b;
            List<Long> seeds = new ArrayList<>();
            try {
                b = areaBounds();
                if (listMode) {
                    if (seedFile == null) throw new IllegalArgumentException(loc.get("error.fileRequired"));
                    try (BufferedReader r = Files.newBufferedReader(seedFile.toPath(), StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            Long s = OutpostSearch.parseSeedLine(line);
                            if (s != null) seeds.add(s);
                        }
                    } catch (IOException e) {
                        throw new IllegalArgumentException(loc.get("error.fileRead", e.getMessage()));
                    }
                    if (seeds.isEmpty()) throw new IllegalArgumentException(loc.get("error.fileEmpty"));
                } else {
                    if (seedField.getText().isBlank()) throw new IllegalArgumentException(loc.get("error.seedRequired"));
                    seeds.add(OutpostSearch.parseSeed(seedField.getText()));
                }
            } catch (IllegalArgumentException e) {
                error(e.getMessage());
                return;
            }
            if ((b[0] < OutpostSearch.WORLD_MIN || b[1] > OutpostSearch.WORLD_MAX
                    || b[2] < OutpostSearch.WORLD_MIN || b[3] > OutpostSearch.WORLD_MAX)
                    && JOptionPane.showConfirmDialog(this, loc.get("warning.outsideWorld"), loc.get("warning.title"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }

            model.clear();
            updateResultTitle();
            control = new OutpostSearch.Control();
            OutpostSearch.Control ctl = control;
            int nThreads = (Integer) threads.getValue();
            int y = (Integer) maxY.getValue();
            long perSeed = OutpostSearch.regionCount(b[0], b[1], b[2], b[3]);
            long total = perSeed * seeds.size();
            AtomicLong regionsDone = new AtomicLong();
            AtomicInteger seedsDone = new AtomicInteger();
            fraction = 0;
            activeMs = 0;
            resumedAt = System.currentTimeMillis();
            setRunning(true);
            clock = new Timer(500, e -> tick(total, regionsDone.get(), seedsDone.get(), seeds.size()));
            clock.start();

            Thread worker = new Thread(() -> {
                String failure = null;
                try {
                    // small areas: one seed per thread (session setup dominates); else all threads on one seed
                    boolean perThread = listMode && perSeed < SMALL_AREA && seeds.size() > 1 && nThreads > 1;
                    int pool = perThread ? nThreads : 1;
                    int threadsPerSeed = perThread ? 1 : nThreads;
                    ExecutorService ex = Executors.newFixedThreadPool(pool);
                    for (long seed : seeds) {
                        ex.submit(() -> {
                            if (ctl.stopped) return;
                            long[] mine = {0};
                            try {
                                boolean finished = OutpostSearch.scan(seed, threadsPerSeed, y, b[0], b[1], b[2], b[3], ctl,
                                        (hits, done) -> {
                                            fraction = regionsDone.addAndGet(done - mine[0]) / (double) total;
                                            mine[0] = done;
                                            if (!hits.isEmpty()) SwingUtilities.invokeLater(() -> addHits(hits));
                                        });
                                if (finished) seedsDone.incrementAndGet();
                            } catch (RuntimeException e) {
                                ctl.stopped = true;
                                SwingUtilities.invokeLater(() -> error(loc.get("error.search", e.getMessage())));
                            }
                        });
                    }
                    ex.shutdown();
                    ex.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (OutOfMemoryError e) {
                    failure = "out of memory";
                }
                boolean stopped = ctl.stopped;
                String fail = failure;
                SwingUtilities.invokeLater(() -> finish(stopped, fail, total, regionsDone.get(), seedsDone.get(), seeds.size()));
            }, "outpost-search");
            worker.setDaemon(true);
            worker.start();
        }

        private void addHits(List<OutpostSearch.Hit> hits) {
            model.addAll(hits);
            if (sorter.getSortKeys().isEmpty()) {
                sorter.setSortKeys(List.of(new RowSorter.SortKey(HitModel.COL_Y, SortOrder.ASCENDING)));
            }
            updateResultTitle();
        }

        private void togglePause() {
            if (control == null) return;
            control.paused = !control.paused;
            long now = System.currentTimeMillis();
            if (control.paused) {
                activeMs += now - resumedAt;
            } else {
                resumedAt = now;
            }
            pause.setText(loc.get(control.paused ? "button.resume" : "button.pause"));
        }

        private void tick(long total, long done, int seedsDone, int seedCount) {
            boolean paused = control != null && control.paused;
            long active = activeMs + (paused ? 0 : System.currentTimeMillis() - resumedAt);
            bar.setValue((int) (fraction * 10_000));
            status.setText(paused ? loc.get("progress.paused") : progressText(total, done, seedsDone, seedCount));
            elapsedLabel.setText(loc.get("progress.elapsed", OutpostSearch.formatDuration(active)));
            String rem = done == 0 || active < 1000 ? loc.get("progress.calculating")
                    : OutpostSearch.formatDuration((long) (active * (double) (total - done) / done));
            remainingLabel.setText(loc.get("progress.remaining", rem));
        }

        private String progressText(long total, long done, int seedsDone, int seedCount) {
            double pct = total == 0 ? 100 : 100.0 * done / total;
            return listMode ? loc.get("progress.seeds", seedsDone, seedCount, pct)
                    : loc.get("progress.running", String.format("%,d", done), String.format("%,d", total), pct);
        }

        private void finish(boolean stopped, String failure, long total, long done, int seedsDone, int seedCount) {
            tick(total, done, seedsDone, seedCount);
            clock.stop();
            control = null;
            setRunning(false);
            status.setText(progressText(total, done, seedsDone, seedCount) + "  -  "
                    + loc.get(stopped ? "progress.stopped" : "progress.done"));
            remainingLabel.setText(" ");
            if (failure != null) error(loc.get("error.search", failure));
        }

        private void setRunning(boolean running) {
            start.setEnabled(!running);
            pause.setEnabled(running);
            stop.setEnabled(running);
            pause.setText(loc.get("button.pause"));
            seedField.setEnabled(!running);
            threads.setEnabled(!running);
            maxY.setEnabled(!running);
            squareBox.setEnabled(!running);
            updateFieldState();
        }

        // ---------------- results ----------------

        private void updateResultTitle() {
            resultBorder.setTitle(loc.get("result.border", model.getRowCount()));
            repaint();
        }

        private void copySelected() {
            int row = table.getSelectedRow();
            if (row < 0) return;
            String tp = model.get(table.convertRowIndexToModel(row)).tp();
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(tp), null);
            status.setText(loc.get("progress.copied", tp));
        }

        private void export(boolean seedsOnly) {
            if (model.getRowCount() == 0) {
                error(loc.get("error.noResults"));
                return;
            }
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File(loc.get(seedsOnly ? "file.seeds" : "file.results")));
            fc.setFileFilter(new FileNameExtensionFilter(loc.get("dialog.textFiles"), "txt"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            if (!f.getName().contains(".")) f = new File(f.getPath() + ".txt");
            List<String> lines = new ArrayList<>();
            Set<Long> seen = new LinkedHashSet<>();
            for (int i = 0; i < table.getRowCount(); i++) {
                OutpostSearch.Hit h = model.get(table.convertRowIndexToModel(i));
                if (seedsOnly) {
                    if (seen.add(h.seed())) lines.add(String.valueOf(h.seed()));
                } else {
                    lines.add(listMode ? h.seed() + "  " + h : h.toString());
                }
            }
            try (PrintWriter w = new PrintWriter(f, StandardCharsets.UTF_8)) {
                lines.forEach(w::println);
            } catch (IOException e) {
                error(loc.get("error.exportFailed", e.getMessage()));
                return;
            }
            JOptionPane.showMessageDialog(this, loc.get("success.export", lines.size()),
                    loc.get("info.title"), JOptionPane.INFORMATION_MESSAGE);
        }

        private void error(String msg) {
            JOptionPane.showMessageDialog(this, msg, loc.get("error.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void onChange(JTextField f, Runnable r) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        });
    }

    // ------------------------------------------------------------------

    private final class HitModel extends AbstractTableModel {
        static final int COL_SEED = 0, COL_TP = 1, COL_Y = 2, COL_PIECE = 3, COL_BASE = 4, COL_DIST = 5;
        private final String[] cols = {loc.get("col.seed"), loc.get("col.tp"), loc.get("col.y"),
                loc.get("col.piece"), loc.get("col.base"), loc.get("col.distance")};
        private final List<OutpostSearch.Hit> rows = new ArrayList<>();

        OutpostSearch.Hit get(int i) {
            return rows.get(i);
        }

        void addAll(List<OutpostSearch.Hit> hits) {
            int first = rows.size();
            rows.addAll(hits);
            fireTableRowsInserted(first, rows.size() - 1);
        }

        void clear() {
            rows.clear();
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return switch (c) {
                case COL_SEED -> Long.class;
                case COL_TP -> String.class;
                case COL_DIST -> Long.class;
                default -> Integer.class;
            };
        }

        @Override
        public Object getValueAt(int r, int c) {
            OutpostSearch.Hit h = rows.get(r);
            return switch (c) {
                case COL_SEED -> h.seed();
                case COL_TP -> h.tp();
                case COL_Y -> h.y();
                case COL_PIECE -> h.pieceY();
                case COL_BASE -> h.baseY();
                default -> Math.round(h.distance());
            };
        }
    }
}
