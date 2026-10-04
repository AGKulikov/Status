/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;
import java.lang.annotation.*;
/** Explicit allowlist of UI-only form constructors. Search never invokes arbitrary actions. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SettingsSearchForm {
    String value() default "";
    String choices() default "";
    String label() default "";
    String kind() default "";
    boolean index() default false;
}
