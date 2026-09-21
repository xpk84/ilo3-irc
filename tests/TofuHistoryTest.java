import java.awt.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.*;
import java.util.prefs.Preferences;
import javax.swing.*;

/** Exercises actual dialog migration/removal ordering with isolated preferences. */
public final class TofuHistoryTest {
    static String key(String host) throws Exception {
        StringBuilder s=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(host.getBytes("UTF-8")))s.append(String.format("%02x",b&255));
        return s.toString();
    }
    public static void main(String[] args) throws Exception {
        Thread watchdog=new Thread(()->{try{Thread.sleep(20000);}catch(Exception e){}System.err.println("FAIL migration UI watchdog");System.exit(2);});watchdog.setDaemon(true);watchdog.start();
        KnownControllers registry=new KnownControllers(Paths.get(args[0],"history","known.properties"));
        Preferences prefs=Preferences.userRoot().node("test-ilo-history-"+System.nanoTime());
        String raw="Synthetic.Invalid:443";
        prefs.put("pin_"+key(raw),TofuFlowTest.A);
        AtomicInteger observed=new AtomicInteger(),forgotten=new AtomicInteger();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(()->{try{
                ConnectionDialog.TrustHistory history=new ConnectionDialog.TrustHistory(){
                    public void migrate(KnownControllers r,String h) throws java.io.IOException {
                        if(!raw.equals(h))throw new java.io.IOException("raw host lost before migration: "+h);
                        LegacyTrustMigration.migrate(r,prefs,h);
                    }
                    public void forget(String h) throws java.io.IOException {
                        if(registry.find(h)==null)throw new java.io.IOException("registry deleted before tombstone");
                        LegacyTrustMigration.forget(prefs,h);forgotten.incrementAndGet();
                    }
                };
                ConnectionDialog dialog=new ConnectionDialog(registry,"",(h,l)->{
                    if(observed.get()==0 && registry.find(h)==null)throw new java.io.IOException("observation ran before migration");
                    observed.incrementAndGet();return TofuFlowTest.cert(TofuFlowTest.B);
                },history);
                JTextField host=(JTextField)TofuFlowTest.named(dialog,"hostField");host.setText(raw);
                AtomicInteger stage=new AtomicInteger();long deadline=System.currentTimeMillis()+12000;
                Timer timer=new Timer(80,null);
                timer.addActionListener(event->{try{
                    if(System.currentTimeMillis()>deadline)throw new AssertionError("migration UI timeout at "+stage.get());
                    if(stage.get()==0){stage.set(1);SwingUtilities.invokeLater(()->((JButton)TofuFlowTest.named(dialog,"connectButton")).doClick());return;}
                    if(stage.get()==1){
                        if(TofuFlowTest.popup("Новый сертификат iLO")!=null)throw new AssertionError("legacy pin lost: first use prompted");
                        JDialog p=TofuFlowTest.popup("Сертификат изменился");if(p==null)return;
                        stage.set(2);p.dispose();return;
                    }
                    if(stage.get()==2 && ((JButton)TofuFlowTest.named(dialog,"connectButton")).isEnabled()){
                        if(!registry.find(raw).fingerprint.equals(TofuFlowTest.A))throw new AssertionError("old pin overwritten");
                        ((JTable)TofuFlowTest.named(dialog,"controllersTable")).setRowSelectionInterval(0,0);
                        stage.set(3);SwingUtilities.invokeLater(()->((JButton)TofuFlowTest.named(dialog,"removeTrustButton")).doClick());return;
                    }
                    if(stage.get()==3){
                        JDialog p=TofuFlowTest.popup("Удалить доверие");if(p==null)return;
                        JOptionPane pane=TofuLayoutTest.find(p,JOptionPane.class);stage.set(4);pane.setValue(JOptionPane.OK_OPTION);return;
                    }
                    if(stage.get()==4 && registry.find(raw)==null){
                        if(forgotten.get()!=1)throw new AssertionError("tombstone callback missing");
                        host.setText(raw);stage.set(5);SwingUtilities.invokeLater(()->((JButton)TofuFlowTest.named(dialog,"connectButton")).doClick());return;
                    }
                    if(stage.get()==5){
                        JDialog p=TofuFlowTest.popup("Новый сертификат iLO");if(p==null)return;
                        stage.set(6);TofuFlowTest.button(p,"Отмена").doClick();return;
                    }
                    if(stage.get()==6 && ((JButton)TofuFlowTest.named(dialog,"connectButton")).isEnabled()){
                        if(registry.find(raw)!=null||observed.get()!=2)throw new AssertionError("deleted trust resurrected or unexpected observation");
                        timer.stop();((JButton)TofuFlowTest.named(dialog,"cancelButton")).doClick();
                    }
                }catch(Throwable e){failure.set(e);timer.stop();for(Window w:Window.getWindows())if(w instanceof JDialog)w.dispose();}});
                timer.start();dialog.setVisible(true);timer.stop();
            }catch(Throwable e){failure.set(e);}});
            if(failure.get()!=null)throw new AssertionError(failure.get());
            System.out.println("PASS UI raw-host migration precedes observation and changed pin blocks");
            System.out.println("PASS UI tombstone precedes deletion; removed trust never reimports");
        }finally{prefs.removeNode();prefs.flush();}
        System.exit(0);
    }
}
