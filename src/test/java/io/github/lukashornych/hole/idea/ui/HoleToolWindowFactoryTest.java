package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HoleToolWindowFactoryTest {

    @Test
    void tabSplittingIsAllowedOnTheToolWindowComponent() {
        final JPanel component = new JPanel();
        final ToolWindow toolWindow = (ToolWindow) Proxy.newProxyInstance(
            ToolWindow.class.getClassLoader(),
            new Class<?>[]{ToolWindow.class},
            (proxy, method, args) -> method.getName().equals("getComponent") ? component : null
        );

        HoleToolWindowFactory.allowTabSplitting(toolWindow);

        assertEquals(Boolean.TRUE, component.getClientProperty(ToolWindowContentUi.ALLOW_DND_FOR_TABS));
    }
}
