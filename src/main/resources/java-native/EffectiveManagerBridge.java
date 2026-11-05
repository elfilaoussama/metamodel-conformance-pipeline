package org.metamodel.nativeworld;

import javax.tools.JavaFileManager;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.util.Context;

/** Modular-javac diagnostic hook, qualified separately for each selected compiler. */
public final class EffectiveManagerBridge {
    public static ConsumedSourceFileManager install(JavacTask task,ConsumedSourceFileManager supplied) {
        Context context=((BasicJavacTask)task).getContext();
        JavaFileManager effective=context.get(JavaFileManager.class);
        if(effective==null)throw new IllegalStateException("effective compiler file manager absent");
        ConsumedSourceFileManager observed=new ConsumedSourceFileManager(effective,supplied);
        context.put(JavaFileManager.class,(JavaFileManager)null);
        context.put(JavaFileManager.class,observed);
        RouteTrace.event("EFFECTIVE_MANAGER_WRAPPED",null,null,
                "effective="+effective.getClass().getName()+"; supplied="+supplied.getClass().getName());
        return observed;
    }
}
