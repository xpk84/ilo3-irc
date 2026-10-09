import java.util.*;
import java.util.regex.*;

public final class MessagesTest {
    private static void check(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Locale display=Locale.getDefault(Locale.Category.DISPLAY);
        Locale format=Locale.getDefault(Locale.Category.FORMAT);
        try {
            ResourceBundle en=new LauncherMessages(),ru=new LauncherMessages_ru();
            check(en.keySet().equals(ru.keySet()),"English/Russian key parity");
            Pattern placeholders=Pattern.compile("\\{[0-9]+\\}");
            for(String key:en.keySet()) {
                Set<String> english=new HashSet<>(),russian=new HashSet<>();
                Matcher e=placeholders.matcher(en.getString(key)),r=placeholders.matcher(ru.getString(key));
                while(e.find())english.add(e.group());
                while(r.find())russian.add(r.group());
                check(english.equals(russian),"Placeholder parity: "+key);
                for(String language:new String[]{"en","ru"}) {
                    Messages.configure(language);
                    String rendered=Messages.text(key,"zero","one","two","three","four","five");
                    check(!placeholders.matcher(rendered).find(),"Unexpanded placeholder: "+key);
                    check(!rendered.trim().isEmpty(),"Empty translation: "+key);
                }
            }
            Locale.setDefault(Locale.Category.DISPLAY,new Locale("ru","RU"));
            Messages.configure("auto");
            check(Messages.text("connect").equals("Подключиться"),"Russian regional locale");
            Messages.configure("en");
            check(Messages.text("connect").equals("Connect"),"English override on Russian system");
            Locale.setDefault(Locale.Category.DISPLAY,Locale.GERMAN);
            Messages.configure("auto");
            check(Messages.text("connect").equals("Connect"),"Unsupported locale falls back to English");
            check(Messages.bundle(Locale.GERMAN).getString("connect").equals("Connect"),"Bundle fallback");
            Locale.setDefault(Locale.Category.DISPLAY,new Locale("ru"));
            check(Messages.bundle(Locale.JAPANESE).getString("connect").equals("Connect"),"Fallback never uses unrelated JVM locale");
            Messages.configure("ru");
            check(Messages.text("cancel").equals("Отмена"),"Russian override");
            check(Locale.getDefault(Locale.Category.FORMAT).equals(format),"Does not change JVM format locale");
            String authority="host'{}:443";
            check(Messages.text("remove.warning",authority).contains(authority),"Values are not parsed as patterns");
            try{Messages.configure("fr");throw new AssertionError("Unsupported override accepted");}
            catch(IllegalArgumentException expected){}
            try{Messages.text("missing.key");throw new AssertionError("Missing key hidden");}
            catch(MissingResourceException expected){}
            System.out.println("PASS translation parity, formatting, locale selection, overrides and fallback");
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY,display);
            Messages.configure("auto");
        }
    }
}
