package org.pischedule.ui;

import org.pischedule.config.AppConfig;

import java.awt.CardLayout;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Owns the window and view, but not the services; future views can share those services. */
public final class ApplicationShell {
    private final JFrame frame;
    private final DashboardView dashboard;
    private final GraphicsDevice device;
    private boolean closed;

    public ApplicationShell(AppConfig config, Runnable onClose) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Construct the UI on the EDT");
        frame = new JFrame("piSchedule");
        device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        dashboard =
                new DashboardView(
                        config.display(), config.transit().stopName(), config.weather().location());
        JPanel views = new JPanel(new CardLayout());
        views.add(dashboard, "dashboard");
        frame.setContentPane(views);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent event) {
                        close(onClose);
                    }
                });
        // An attached keyboard can exit kiosk mode; the touchscreen requires no controls.
        views.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        views.getActionMap()
                .put(
                        "close",
                        new AbstractAction() {
                            @Override
                            public void actionPerformed(ActionEvent event) {
                                close(onClose);
                            }
                        });
        if (config.display().fullScreen()) {
            frame.setUndecorated(true);
            frame.setBounds(device.getDefaultConfiguration().getBounds());
            frame.setVisible(true);
            if (device.isFullScreenSupported()) device.setFullScreenWindow(frame);
        } else {
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }
    }

    public DashboardView dashboard() {
        return dashboard;
    }

    private void close(Runnable onClose) {
        if (closed) return;
        closed = true;
        dashboard.stop();
        if (device.getFullScreenWindow() == frame) device.setFullScreenWindow(null);
        frame.dispose();
        onClose.run();
    }
}
