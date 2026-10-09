import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/** Launcher UI language; does not change the controller applet's JVM locale. */
final class Messages {
    private static Locale locale = select(Locale.getDefault(Locale.Category.DISPLAY));
    private static final ResourceBundle.Control CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_CLASS);

    private Messages() {}

    private static Locale select(Locale requested) {
        return "ru".equals(requested.getLanguage()) ? requested : Locale.ENGLISH;
    }

    static void configure(String language) {
        if ("auto".equals(language)) locale = select(Locale.getDefault(Locale.Category.DISPLAY));
        else if ("en".equals(language)) locale = Locale.ENGLISH;
        else if ("ru".equals(language)) locale = new Locale("ru");
        else throw new IllegalArgumentException("Unsupported language: " + language + "; use en, ru or auto");
    }

    static Locale locale() { return locale; }

    static ResourceBundle bundle(Locale requested) {
        return ResourceBundle.getBundle("LauncherMessages", requested, CONTROL);
    }

    static String text(String key, Object... arguments) {
        String pattern = bundle(locale).getString(key);
        return arguments.length == 0 ? pattern : new MessageFormat(pattern, locale).format(arguments);
    }
}
