/*
 * Copyright 2013 Red Hat, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.jboss.errai.idea.plugin.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import org.jboss.errai.idea.plugin.util.FileTemplateUtil;
import org.jboss.errai.idea.plugin.util.Types;

import java.util.Collections;
import java.util.HashMap;

/**
 * @author Mike Brock
 */
public class NewTemplatedWidgetAction extends AnAction {
  @Override
  public void actionPerformed(AnActionEvent e) {
    final VirtualFile virtualFile = e.getData(PlatformDataKeys.VIRTUAL_FILE);
    final VirtualFile directory;

    if (virtualFile.isDirectory()) {
      directory = virtualFile;
    }
    else {
      directory = virtualFile.getParent();
    }

    final String name = Messages.showInputDialog(e.getProject(),
        "Type a new name for your Errai UI template. (Do not include file extension)",
        "Create new Errai UI template",
        ErraiActionGroup.ERRAI_ICON, "", new InputValidator() {

      boolean valid = false;

      private boolean validate(String name) {
        final String trimmed = name.trim();

        return trimmed.length() != 0
            && trimmed.indexOf('.') == -1
            && !(!Character.isLetter(trimmed.charAt(0))
            || !trimmed.matches("^[A-Za-z0-9_]*$"));
      }

      @Override
      public boolean checkInput(String inputString) {
        this.valid = validate(inputString);
        return valid;
      }

      @Override
      public boolean canClose(String inputString) {
        return valid;
      }
    });

    if (name == null) {
      return;
    }

    final String trimmed = name.trim();



    final PsiDirectory psiDirectory = PsiManager.getInstance(e.getProject()).findDirectory(directory);

    final PsiElement templateFile = FileTemplateUtil.createFileFromTemplate("TemplatedBean.java", trimmed, psiDirectory
        , new HashMap<String, String>() {
      {
        put("TEMPLATED_ANNOTATION_TYPE", Types.TEMPLATED);
        put("GWT_COMPOSITE_TYPE", Types.GWT_COMPOSITE);
        put("JAVAX_INJECT_TYPE", Types.JAVAX_INJECT);
      }
    });

    FileTemplateUtil.createFileFromTemplate("TemplatedFile.html", name, psiDirectory, Collections.<String, String>emptyMap());

    templateFile.getContainingFile().navigate(true);
  }
}
