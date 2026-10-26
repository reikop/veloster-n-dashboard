import com.veloster.i18n.UiText;
import com.veloster.vehicle.Telemetry;
import java.util.Arrays;
import java.util.Locale;

public final class UiTextTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        Locale system=Locale.getDefault();
        String[] original=Telemetry.LABELS.clone();
        check("ko".equals(UiText.resolve("auto","ko-KR")),"Korean system language");
        for(String locale:new String[]{"en","de","fr","ja","",null})
            check("en".equals(UiText.resolve("auto",locale)),"non-Korean system falls back to English");
        check("ko".equals(UiText.resolve("ko","en")),"explicit Korean overrides system");
        check("en".equals(UiText.resolve("en","ko")),"explicit English overrides system");
        check("ko".equals(UiText.resolve("bad","ko")),"corrupt preference uses system");
        UiText.configure("en","ko");
        check("N Dashboard".equals(UiText.t("N 계기판")),"dashboard label translated");
        check("Quick entry".equals(UiText.t("실행 바로가기 / Quick entry")),"quick entry translated");
        String[] english=UiText.list(Telemetry.LABELS);
        for(String label:english)check(!label.matches(".*[가-힣].*"),"English signal label: "+label);
        english[0]="changed copy";
        check(Arrays.equals(original,Telemetry.LABELS),"translation cannot mutate signal IDs/labels");
        check("120 km/h".equals(UiText.t("120 km/h")),"unit/value strings unchanged");
        check("Song 한국어 title".equals(UiText.t("Song 한국어 title")),"unknown metadata preserved");
        UiText.configure("ko","en");
        check("N 계기판".equals(UiText.t("N 계기판")),"manual Korean roundtrip");
        check("저장".equals(UiText.t("저장 / Save")),"legacy bilingual key renders one language");
        check(Locale.getDefault().equals(system),"display choice must not alter the system/numeric locale");
        System.out.println("PASS: language fallback/override, signal labels, metadata and locale isolation");
    }
}
