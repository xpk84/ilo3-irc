import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.text.DateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Single connection window and an explicit, password-free known-controller registry. */
final class ConnectionDialog extends JDialog {
    private static final long serialVersionUID=1L;
    interface Observer { SecureIlo.CertificateInfo observe(String host,boolean legacy) throws Exception; }
    interface TrustHistory {
        void migrate(KnownControllers registry,String rawHost) throws IOException;
        void forget(String authority) throws IOException;
    }
    private final TrustHistory history;
    static final class Request {
        final String host,user,pin; final char[] password; final boolean legacy;
        Request(String h,String u,char[] p,String pin,boolean legacy){host=h;user=u;password=p;this.pin=pin;this.legacy=legacy;}
    }
    private final KnownControllers registry;
    private final Observer observer;
    private final JTextField host=new JTextField(28), user=new JTextField(28), expected=new JTextField(40);
    private final JPasswordField password=new JPasswordField(28);
    private final JCheckBox legacy=new JCheckBox("Разрешить устаревший TLS 1.0/1.1 (для старых iLO)");
    private final JLabel status=new JLabel("Новый сервер спросит подтверждение сертификата перед отправкой пароля.");
    private final DefaultTableModel model=new DefaultTableModel(new String[]{"Имя","Адрес","Доверие","Последний вход"},0){
        private static final long serialVersionUID=1L;
        public boolean isCellEditable(int r,int c){return false;}
    };
    private final JTable table=new JTable(model);
    private final JButton connect=new JButton("Подключиться"),details=new JButton("Сертификат…"),rename=new JButton("Переименовать…"),remove=new JButton("Удалить доверие…");
    private final JButton cancel=new JButton("Отмена");
    private final Set<String> changed=new HashSet<>();
    private List<KnownControllers.Entry> rows=new ArrayList<>();
    private SwingWorker<SecureIlo.CertificateInfo,Void> worker;
    private Request result;
    private boolean busy;

    static Request open(KnownControllers registry,String initial) throws Exception {
        final Request[] result=new Request[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                ConnectionDialog dialog=new ConnectionDialog(registry,initial,SecureIlo::observeIsolatedCertificate,new TrustHistory(){
                    public void migrate(KnownControllers r,String h) throws IOException {ILO3IRC.migrateLegacyPin(r,h);}
                    public void forget(String h) throws IOException {ILO3IRC.forgetLegacyPin(h);}
                });
                dialog.setVisible(true);result[0]=dialog.result;
            } catch(IOException ex){throw new java.io.UncheckedIOException(ex);}
        });
        return result[0];
    }

    ConnectionDialog(KnownControllers registry,String initial,Observer observer) throws IOException {
        this(registry,initial,observer,new TrustHistory(){
            public void migrate(KnownControllers r,String h){}
            public void forget(String h){}
        });
    }
    ConnectionDialog(KnownControllers registry,String initial,Observer observer,TrustHistory history) throws IOException {
        super((Frame)null,"iLO 3 Console",true);this.registry=registry;this.observer=observer;this.history=history;
        if(!initial.isEmpty())history.migrate(registry,initial);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){cancel();}});
        host.setName("hostField");user.setName("usernameField");password.setName("passwordField");expected.setName("optionalFingerprintField");
        table.setName("controllersTable");connect.setName("connectButton");cancel.setName("cancelButton");legacy.setName("legacyTlsCheck");
        details.setName("certificateDetailsButton");rename.setName("renameControllerButton");remove.setName("removeTrustButton");
        host.setText(initial);
        JPanel form=new JPanel(new GridLayout(3,2,10,8));
        form.add(new JLabel("Адрес iLO (IP/DNS и порт):"));form.add(host);
        form.add(new JLabel("Логин:"));form.add(user);form.add(new JLabel("Пароль:"));form.add(password);
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));top.add(form);top.add(Box.createVerticalStrut(6));top.add(legacy);
        JToggleButton advanced=new JToggleButton("Дополнительно: независимая сверка отпечатка");advanced.setName("advancedTrustButton");advanced.setAlignmentX(LEFT_ALIGNMENT);
        JPanel advancedPanel=new JPanel(new BorderLayout(8,0));advancedPanel.add(new JLabel("Ожидаемый SHA-256 (необязательно):"),BorderLayout.WEST);advancedPanel.add(expected);advancedPanel.setVisible(false);
        advanced.addActionListener(e -> {advancedPanel.setVisible(advanced.isSelected());pack();});
        top.add(advanced);top.add(advancedPanel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setFillsViewportHeight(true);table.setRowHeight(25);
        // Registry aliases are text, not HTML (avoid renderer-driven URL loads).
        table.setDefaultRenderer(Object.class,new javax.swing.table.DefaultTableCellRenderer(){
            private static final long serialVersionUID=1L;
            public Component getTableCellRendererComponent(JTable t,Object v,boolean selected,boolean focus,int row,int col){
                putClientProperty("html.disable",Boolean.TRUE);
                return super.getTableCellRendererComponent(t,v,selected,focus,row,col);
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if(!busy&&!e.getValueIsAdjusting()){
                KnownControllers.Entry entry=selected();
                if(entry!=null){host.setText(entry.authority);legacy.setSelected(entry.legacyTls);password.setText("");expected.setText("");}
            }
        });
        JScrollPane scroll=new JScrollPane(table);scroll.setPreferredSize(new Dimension(800,160));
        JPanel registryPanel=new JPanel(new BorderLayout(0,6));registryPanel.setBorder(BorderFactory.createTitledBorder("Известные серверы — пароли не сохраняются"));registryPanel.add(scroll);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));actions.add(details);actions.add(rename);actions.add(remove);registryPanel.add(actions,BorderLayout.SOUTH);
        JPanel bottom=new JPanel(new BorderLayout(0,8));bottom.add(status,BorderLayout.NORTH);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));buttons.add(cancel);buttons.add(connect);bottom.add(buttons,BorderLayout.SOUTH);
        JPanel content=new JPanel(new BorderLayout(0,12));content.setBorder(BorderFactory.createEmptyBorder(14,14,14,14));content.add(top,BorderLayout.NORTH);content.add(registryPanel);content.add(bottom,BorderLayout.SOUTH);setContentPane(content);
        connect.addActionListener(e -> beginConnection());cancel.addActionListener(e -> cancel());
        details.addActionListener(e -> showDetails());rename.addActionListener(e -> rename());remove.addActionListener(e -> remove());
        getRootPane().setDefaultButton(connect);
        getRootPane().registerKeyboardAction(e -> cancel(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
        refresh();
        if(!initial.isEmpty()){
            KnownControllers.Entry entry=registry.find(initial);
            if(entry!=null){host.setText(entry.authority);legacy.setSelected(entry.legacyTls);}
        }
        pack();setLocationRelativeTo(null);
    }

    private void cancel(){if(worker!=null)worker.cancel(true);password.setText("");dispose();}
    private void busy(boolean value){
        busy=value;for(Component c:new Component[]{host,user,password,expected,legacy,table,connect,details,rename,remove})c.setEnabled(!value);
    }
    private void refresh() throws IOException {
        rows=registry.list();model.setRowCount(0);
        for(KnownControllers.Entry row:rows)model.addRow(new Object[]{row.name,row.authority,changed.contains(row.authority)?"СЕРТИФИКАТ ИЗМЕНИЛСЯ":"Сертификат сохранён",date(row.lastConnected)});
    }
    private KnownControllers.Entry selected(){int i=table.getSelectedRow();return i<0||i>=rows.size()?null:rows.get(i);}
    private static String date(long t){return t<=0?"—":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(t));}
    private static String describe(String authority,SecureIlo.CertificateInfo cert){
        return "Сервер: "+authority+"\nSHA-256: "+TrustPolicy.normalize(cert.fingerprint)+"\n\nСубъект: "+cert.subject+"\nИздатель: "+cert.issuer+"\nДействителен с: "+date(cert.notBefore)+"\nДо: "+date(cert.notAfter);
    }
    private static JScrollPane text(String value){
        JTextArea area=new JTextArea(value,13,68);area.setEditable(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setCaretPosition(0);return new JScrollPane(area);
    }
    private void error(Exception failure){
        Throwable cause=failure instanceof ExecutionException && failure.getCause()!=null?failure.getCause():failure;
        String message=cause.getMessage()==null?cause.getClass().getSimpleName():cause.getMessage();
        JOptionPane.showMessageDialog(this,text(message),"Подключение не выполнено",JOptionPane.ERROR_MESSAGE);
        status.setText("Не подключено. Проверьте адрес, маршрут и настройку устаревшего TLS.");
    }
    private boolean acceptFirst(String authority,SecureIlo.CertificateInfo cert){
        String warning="Первое подключение: подлинность сервера ещё не подтверждена.\n"
                +"Принятие запомнит этот сертификат и обнаружит его подмену при следующих подключениях.\n"
                +"Перехват уже первого соединения этим не исключается; при сомнении отмените и сверьте отпечаток отдельно.\n"
                +"Логин/пароль ещё не отправлены; Java-код не скачан.\n\n"+describe(authority,cert);
        Object[] choices={"Принять и запомнить","Отмена"};
        return JOptionPane.showOptionDialog(this,text(warning),"Новый сертификат iLO",JOptionPane.DEFAULT_OPTION,JOptionPane.WARNING_MESSAGE,null,choices,choices[1])==0;
    }

    private void beginConnection(){
        if(busy)return;
        final String rawHost=host.getText().trim();
        final String authority;
        try{authority=KnownControllers.canonicalAuthority(rawHost);}
        catch(Exception ex){error(ex);return;}
        final String username=user.getText().trim(),optional=expected.getText().trim();
        final char[] secret=password.getPassword();final boolean legacyTls=legacy.isSelected();
        busy(true);status.setText("Получаю сертификат: только TLS-рукопожатие, без отправки пароля…");
        worker=new SwingWorker<SecureIlo.CertificateInfo,Void>(){
            protected SecureIlo.CertificateInfo doInBackground() throws Exception{
                history.migrate(registry,rawHost);
                return observer.observe(authority,legacyTls);
            }
            protected void done(){
                boolean handedOff=false;
                try{
                    if(!isDisplayable()||isCancelled())return;
                    SecureIlo.CertificateInfo cert=get();
                    KnownControllers.Entry saved=registry.find(authority);
                    String pin=TrustPolicy.authorize(saved==null?null:saved.fingerprint,cert.fingerprint,optional,() -> acceptFirst(authority,cert));
                    if(saved==null)registry.accept(authority,authority,pin,cert.subject,cert.issuer,cert.notBefore,cert.notAfter,legacyTls,System.currentTimeMillis());
                    KnownControllers.Entry current=registry.find(authority);
                    if(current==null||!current.fingerprint.equals(pin))throw new IOException("Доверие изменено другим окном. Подключитесь заново.");
                    changed.remove(authority);refresh();
                    if(username.isEmpty()||secret.length==0){status.setText("Сертификат сохранён/проверен. Введите логин и пароль.");return;}
                    result=new Request(authority,username,secret,pin,legacyTls);handedOff=true;password.setText("");dispose();
                }catch(TrustPolicy.Declined ex){password.setText("");status.setText("Сертификат не принят. Логин и пароль не отправлены.");}
                catch(TrustPolicy.Changed ex){
                    changed.add(authority);password.setText("");
                    try{refresh();}catch(IOException ioe){error(ioe);}
                    JOptionPane.showMessageDialog(ConnectionDialog.this,text("Подключение заблокировано. Пароль не отправлен.\n\nСервер: "+authority+"\nСохранённый SHA-256: "+ex.previous+"\nПолученный SHA-256: "+ex.current+"\n\nПроверьте причину смены. Замена доступна отдельно: выберите сервер → Сертификат… → Заменить сертификат…"),"Сертификат изменился",JOptionPane.ERROR_MESSAGE);
                    status.setText("Сертификат изменился — подключение запрещено до явного решения.");
                }catch(Exception ex){password.setText("");error(ex);}
                finally{if(!handedOff)Arrays.fill(secret,'\0');busy(false);}
            }
        };worker.execute();
    }

    private void rename(){
        KnownControllers.Entry e=selected();if(e==null)return;
        String name=JOptionPane.showInputDialog(this,"Имя сервера:",e.name);
        if(name!=null)try{registry.rename(e.authority,name);refresh();}catch(Exception ex){error(ex);}
    }
    private void remove(){
        KnownControllers.Entry e=selected();if(e==null)return;
        if(JOptionPane.showConfirmDialog(this,text("Удалить доверие к "+e.authority+"?\nСледующее подключение снова потребует подтверждения сертификата."),"Удалить доверие",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;
        try{history.forget(e.authority);registry.remove(e.authority);changed.remove(e.authority);refresh();}catch(Exception ex){error(ex);}
    }
    private void showDetails(){
        KnownControllers.Entry e=selected();if(e==null)return;
        SecureIlo.CertificateInfo cert=new SecureIlo.CertificateInfo(e.fingerprint,e.subject,e.issuer,e.notBefore,e.notAfter);
        Object[] options={"Закрыть","Заменить сертификат…"};
        boolean selectedTls=e.legacyTls;
        try{if(e.authority.equals(KnownControllers.canonicalAuthority(host.getText().trim())))selectedTls=legacy.isSelected();}catch(IllegalArgumentException ignored){}
        JCheckBox replacementTls=new JCheckBox("Разрешить устаревший TLS 1.0/1.1 для этого сервера",selectedTls);
        replacementTls.setName("replacementLegacyTlsCheck");
        JPanel panel=new JPanel(new BorderLayout(0,8));panel.add(text(describe(e.authority,cert)+"\n\nПринят: "+date(e.acceptedAt)+"\nПоследний успешный вход: "+date(e.lastConnected)));panel.add(replacementTls,BorderLayout.SOUTH);
        int choice=JOptionPane.showOptionDialog(this,panel,"Сохранённый сертификат",JOptionPane.DEFAULT_OPTION,JOptionPane.INFORMATION_MESSAGE,null,options,options[0]);
        if(choice==1)replaceCertificate(e,replacementTls.isSelected());
    }
    private void replaceCertificate(KnownControllers.Entry previous,boolean replacementLegacyTls){
        password.setText("");busy(true);status.setText("Получаю новый сертификат для отдельной замены доверия…");
        worker=new SwingWorker<SecureIlo.CertificateInfo,Void>(){
            protected SecureIlo.CertificateInfo doInBackground() throws Exception{return observer.observe(previous.authority,replacementLegacyTls);}
            protected void done(){try{
                if(!isDisplayable()||isCancelled())return;
                SecureIlo.CertificateInfo cert=get();String pin=TrustPolicy.normalize(cert.fingerprint);
                if(previous.fingerprint.equals(pin)){status.setText("Сертификат совпадает с сохранённым — замена не нужна.");return;}
                String warning="Это изменение доверия, НЕ продолжение подключения.\nУбедитесь, что сертификат изменён вами/администратором, а не посредником.\n\nСтарый SHA-256: "+previous.fingerprint+"\n\n"+describe(previous.authority,cert)+"\n\nПароль не отправляется. Для подключения затем нажмите «Подключиться».";
                Object[] choices={"Заменить сохранённый сертификат","Отмена"};
                if(JOptionPane.showOptionDialog(ConnectionDialog.this,text(warning),"Подтвердите замену доверия",JOptionPane.DEFAULT_OPTION,JOptionPane.WARNING_MESSAGE,null,choices,choices[1])!=0)return;
                registry.replace(previous.authority,previous.fingerprint,pin,cert.subject,cert.issuer,cert.notBefore,cert.notAfter,replacementLegacyTls,System.currentTimeMillis());
                changed.remove(previous.authority);refresh();status.setText("Сертификат заменён по вашему подтверждению. Подключение не выполнялось.");
            }catch(Exception ex){error(ex);}finally{busy(false);}}
        };worker.execute();
    }
}
