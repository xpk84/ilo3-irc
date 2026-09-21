import java.awt.*;
import javax.swing.*;

/** GUI layout regression: observes only this JVM's controls and cancels, never sends credentials. */
public final class TofuLayoutTest {
    static <T> T find(Component c,Class<T> type) {
        if(type.isInstance(c))return type.cast(c);
        if(c instanceof Container)for(Component child:((Container)c).getComponents()){T found=find(child,type);if(found!=null)return found;}
        return null;
    }
    public static void main(String[] args) {
        Thread timeout=new Thread(() -> {try{Thread.sleep(15000);}catch(Exception e){}System.err.println("FAIL login dialog missing");System.exit(2);});timeout.setDaemon(true);timeout.start();
        SwingUtilities.invokeLater(() -> {
            Timer timer=new Timer(250,e -> {
                for(Window window:Window.getWindows())if(window instanceof JDialog && window.isShowing() && ((JDialog)window).getTitle().equals("iLO 3/4 Console")) {
                    if(find(window,JTable.class)==null){System.err.println("FAIL known-controller registry table absent");System.exit(1);}
                    if(find(window,JPasswordField.class)==null){System.err.println("FAIL masked password field absent");System.exit(1);}
                    System.out.println("PASS known-controller table and masked password field visible");
                    ((Timer)e.getSource()).stop();window.dispatchEvent(new java.awt.event.WindowEvent(window,java.awt.event.WindowEvent.WINDOW_CLOSING));
                    System.out.println("PASS cancel without credentials/network");
                }
            });timer.start();
        });
        ILO3IRC.main(new String[0]);
    }
}
