import java.awt.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Synthetic observations only: no real host, passwords or HTTP. */
public final class TofuFlowTest {
    static final String A=String.join("",java.util.Collections.nCopies(64,"A"));
    static final String B=String.join("",java.util.Collections.nCopies(64,"B"));
    static int passed;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);passed++;System.out.println("PASS "+message);}
    static Component named(Component root,String name){
        if(name.equals(root.getName()))return root;
        if(root instanceof Container)for(Component child:((Container)root).getComponents()){Component found=named(child,name);if(found!=null)return found;}return null;
    }
    static JButton button(Component root,String label){
        if(root instanceof JButton && label.equals(((JButton)root).getText()))return (JButton)root;
        if(root instanceof Container)for(Component child:((Container)root).getComponents()){JButton found=button(child,label);if(found!=null)return found;}return null;
    }
    static JDialog popup(String title){for(Window w:Window.getWindows())if(w instanceof JDialog&&w.isShowing()&&title.equals(((JDialog)w).getTitle()))return (JDialog)w;return null;}
    static SecureIlo.CertificateInfo cert(String pin){return new SecureIlo.CertificateInfo(pin,"CN=synthetic","CN=synthetic",1,2000000000000L);}
    static void scenario(KnownControllers registry,String pin,boolean accept,boolean changed) throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {try{
            AtomicInteger calls=new AtomicInteger();
            ConnectionDialog d=new ConnectionDialog(registry,"synthetic.invalid",(h,l)->{calls.incrementAndGet();return cert(pin);});
            AtomicInteger stage=new AtomicInteger();long deadline=System.currentTimeMillis()+8000;
            Timer timer=new Timer(70,null);
            timer.addActionListener(event->{try{
                if(System.currentTimeMillis()>deadline)throw new AssertionError("UI scenario timed out at "+stage.get());
                if(stage.get()==0){stage.set(1);((JButton)named(d,"connectButton")).doClick();return;}
                if(stage.get()==1){
                    JDialog p=popup(Messages.text(changed?"changed.title":"first.title"));
                    if(p==null)return;
                    stage.set(2);
                    if(!changed)check(Messages.text("cancel").equals(TofuLayoutTest.find(p,JOptionPane.class).getInitialValue()),"first-use approval defaults to cancel");
                    JButton choice=button(p,Messages.text(changed?"ok":accept?"accept":"cancel"));
                    if(choice==null && changed){p.dispatchEvent(new java.awt.event.WindowEvent(p,java.awt.event.WindowEvent.WINDOW_CLOSING));}
                    else {if(choice==null)throw new AssertionError("Missing confirmation action");choice.doClick();}
                    return;
                }
                if(stage.get()==2 && ((JButton)named(d,"connectButton")).isEnabled()){
                    check(calls.get()==1,"one observation per attempt");
                    KnownControllers.Entry saved=registry.find("synthetic.invalid");
                    check(changed? saved.fingerprint.equals(A):accept?saved!=null:saved==null,"registry reflects explicit decision only");
                    JTable table=(JTable)named(d,"controllersTable");
                    check(table.getRowCount()==(accept||changed?1:0),"registry table refreshed");
                    if(changed)check(table.getValueAt(0,2).equals(Messages.text("trust.changed")),"changed certificate flagged in table");
                    timer.stop();((JButton)named(d,"cancelButton")).doClick();
                }
            }catch(Throwable e){failure.set(e);timer.stop();d.dispose();}});
            timer.start();d.setVisible(true);timer.stop();
        }catch(Throwable e){failure.set(e);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    static void known(KnownControllers registry) throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{
            ConnectionDialog d=new ConnectionDialog(registry,"",(h,l)->cert(A));
            JTable table=(JTable)named(d,"controllersTable");table.setRowSelectionInterval(0,0);
            check(((JTextField)named(d,"hostField")).getText().equals("synthetic.invalid"),"selection fills saved authority");
            Timer timer=new Timer(100,null);AtomicInteger phase=new AtomicInteger();long deadline=System.currentTimeMillis()+5000;
            timer.addActionListener(e->{try{
                if(System.currentTimeMillis()>deadline)throw new AssertionError("Known controller timed out");
                if(phase.getAndIncrement()==0){((JButton)named(d,"connectButton")).doClick();return;}
                check(popup(Messages.text("first.title"))==null,"known matching controller never asks first-use approval");
                if(((JButton)named(d,"connectButton")).isEnabled()){timer.stop();((JButton)named(d,"cancelButton")).doClick();}
            }catch(Throwable x){failure.set(x);timer.stop();d.dispose();}});
            timer.start();d.setVisible(true);timer.stop();
        }catch(Throwable e){failure.set(e);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    static void replaceWithSelectedTls(KnownControllers registry) throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{
            AtomicInteger observations=new AtomicInteger();
            ConnectionDialog d=new ConnectionDialog(registry,"",(host,legacy)->{
                if(!legacy)throw new java.io.IOException("Synthetic controller requires explicitly selected legacy TLS");
                observations.incrementAndGet();return cert(B);
            });
            JTable table=(JTable)named(d,"controllersTable");table.setRowSelectionInterval(0,0);
            ((JCheckBox)named(d,"legacyTlsCheck")).setSelected(true);
            AtomicInteger stage=new AtomicInteger();long deadline=System.currentTimeMillis()+7000;
            Timer timer=new Timer(80,null);
            timer.addActionListener(event->{try{
                if(System.currentTimeMillis()>deadline)throw new AssertionError("replacement ignored selected TLS or timed out at "+stage.get());
                if(stage.get()==0){stage.set(1);SwingUtilities.invokeLater(()->((JButton)named(d,"certificateDetailsButton")).doClick());return;}
                if(stage.get()==1){JDialog p=popup(Messages.text("certificate.title"));if(p==null)return;stage.set(2);SwingUtilities.invokeLater(()->button(p,Messages.text("replace")).doClick());return;}
                if(stage.get()==2){JDialog p=popup(Messages.text("replacement.title"));if(p==null)return;check(Messages.text("cancel").equals(TofuLayoutTest.find(p,JOptionPane.class).getInitialValue()),"replacement defaults to cancel");stage.set(3);SwingUtilities.invokeLater(()->button(p,Messages.text("replacement.accept")).doClick());return;}
                if(stage.get()==3 && ((JButton)named(d,"connectButton")).isEnabled()){
                    KnownControllers.Entry saved=registry.find("synthetic.invalid");
                    check(saved.fingerprint.equals(B)&&saved.legacyTls,"explicit replacement persists selected TLS and new pin");
                    check(observations.get()==1&&saved.lastConnected==0,"replacement observes once and does not log in");
                    timer.stop();((JButton)named(d,"cancelButton")).doClick();
                }
            }catch(Throwable e){failure.set(e);timer.stop();for(Window w:Window.getWindows())if(w instanceof JDialog)w.dispose();}});
            timer.start();d.setVisible(true);timer.stop();
        }catch(Throwable e){failure.set(e);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    public static void main(String[] args) throws Exception {
        Thread watchdog=new Thread(()->{try{Thread.sleep(30000);}catch(Exception e){}System.err.println("FAIL UI watchdog");System.exit(2);});watchdog.setDaemon(true);watchdog.start();
        KnownControllers registry=new KnownControllers(Paths.get(args[0],"registry","known.properties"));
        scenario(registry,A,false,false);scenario(registry,A,true,false);known(registry);scenario(registry,B,false,true);replaceWithSelectedTls(registry);rename(registry);
        check(!new String(Files.readAllBytes(Paths.get(args[0],"registry","known.properties")),"ISO-8859-1").contains("password"),"registry contains no password field");
        System.out.println("TOFU UI FLOW TESTS: "+passed+" PASS");System.exit(0);
    }
    static void rename(KnownControllers registry) throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{
            ConnectionDialog d=new ConnectionDialog(registry,"",(h,l)->cert(B));
            ((JTable)named(d,"controllersTable")).setRowSelectionInterval(0,0);
            AtomicInteger stage=new AtomicInteger();long deadline=System.currentTimeMillis()+5000;
            Timer timer=new Timer(80,null);
            timer.addActionListener(event->{try{
                if(System.currentTimeMillis()>deadline)throw new AssertionError("rename timed out at "+stage.get());
                if(stage.get()==0){stage.set(1);SwingUtilities.invokeLater(()->((JButton)named(d,"renameControllerButton")).doClick());return;}
                if(stage.get()==1){
                    JDialog p=popup(Messages.text("rename"));if(p==null)return;
                    JTextField field=TofuLayoutTest.find(p,JTextField.class);
                    check(field.getText().equals("synthetic.invalid"),"rename initially shows current alias");
                    field.setText("Test alias");stage.set(2);button(p,Messages.text("ok")).doClick();return;
                }
                if(stage.get()==2){
                    check(registry.find("synthetic.invalid").name.equals("Test alias"),"localized rename saves entered alias");
                    ((JTable)named(d,"controllersTable")).setRowSelectionInterval(0,0);
                    stage.set(3);SwingUtilities.invokeLater(()->((JButton)named(d,"renameControllerButton")).doClick());return;
                }
                if(stage.get()==3){
                    JDialog p=popup(Messages.text("rename"));if(p==null)return;
                    TofuLayoutTest.find(p,JTextField.class).setText("Do not save");
                    stage.set(4);button(p,Messages.text("cancel")).doClick();return;
                }
                if(stage.get()==4){
                    check(registry.find("synthetic.invalid").name.equals("Test alias"),"localized rename cancel preserves alias");
                    timer.stop();d.dispose();
                }
            }catch(Throwable e){failure.set(e);timer.stop();for(Window w:Window.getWindows())if(w instanceof JDialog)w.dispose();}});
            timer.start();d.setVisible(true);timer.stop();
        }catch(Throwable e){failure.set(e);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
}
