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

package org.jboss.errai.idea.plugin.databinding;

import com.intellij.lang.properties.IProperty;
import com.intellij.lang.properties.PropertiesUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;
import org.jboss.errai.idea.plugin.databinding.model.BindabilityValidation;
import org.jboss.errai.idea.plugin.databinding.model.BoundMetaData;
import org.jboss.errai.idea.plugin.databinding.model.ConvertibilityMetaData;
import org.jboss.errai.idea.plugin.databinding.model.PropertyInfo;
import org.jboss.errai.idea.plugin.databinding.model.TemplateBindingMetaData;
import org.jboss.errai.idea.plugin.util.AnnotationSearchResult;
import org.jboss.errai.idea.plugin.util.CacheProvider;
import org.jboss.errai.idea.plugin.util.DefaultPolicy;
import org.jboss.errai.idea.plugin.util.SuperTypeInfo;
import org.jboss.errai.idea.plugin.util.Types;
import org.jboss.errai.idea.plugin.util.Util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 * @author Mike Brock
 */
public class DataBindUtil {
  private static final int CASE_OFFSET = ('z' - 'Z');
  private static final Key<TemplateBindingMetaData> TEMPLATE_BINDING_META_DATA_KEY
      = Key.create("TEMPLATE_BINDING_META_DATA_KEY");

  public static Map<String, PropertyInfo> getAllProperties(PsiClass boundClass, String propertySearchRoot) {
    final Set<String> bindableTypes = getConfiguredBindableTypes(boundClass.getProject());

    int idx = propertySearchRoot.lastIndexOf('.');
    if (idx == -1) {
      propertySearchRoot = null;
    }
    else {
      propertySearchRoot = propertySearchRoot.substring(0, idx);
    }

    PsiClass cls = boundClass;
    if (propertySearchRoot != null) {
      for (String token : propertySearchRoot.split("\\.")) {
        if (!bindableTypes.contains(cls.getQualifiedName()) && !Util.elementIsAnnotated(cls, Types.BINDABLE)) {
          cls = null;
          break;
        }
        PsiClass result = getBeanPropertyType(cls, token.trim());
        if (result == null) {
          cls = null;
          break;
        }
        cls = result;
      }
    }

    Map<String, PropertyInfo> properties = new LinkedHashMap<String, PropertyInfo>();
    Project project = boundClass.getProject();
    final Map<String, PropertyInfo> propertyInfoMap = new LinkedHashMap<String, PropertyInfo>();
    for (final PsiMethod method : cls.getAllMethods()) {
      if (method.getModifierList().hasModifierProperty("public")) {
        final PsiClass topLevelClass = PsiUtil.getTopLevelClass(method);
        if (topLevelClass == null || topLevelClass.getQualifiedName().equals("java.lang.Object")) {
          continue;
        }

        final String property = getPropertyFromAccessor(method.getName());

        final PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length == 0
            && (method.getName().equalsIgnoreCase("get" + property)) || method.getName().equalsIgnoreCase("is" + property)) {

          PsiClass type = getPsiClassFromType(project, method.getReturnType());

          final PropertyInfo info = getOrCreatePropertyInfo(propertyInfoMap, property);
          info.setGetterElement(method);
          if (info.getPropertyType() == null) {
            info.setPropertyType(type);
          }
        }
        else if (parameters.length == 1 && method.getName().equalsIgnoreCase("set" + property)) {
          PsiClass type = getPsiClassFromType(project, parameters[0].getType());
          final PropertyInfo info = getOrCreatePropertyInfo(propertyInfoMap, property);
          info.setSetterElement(method);
          if (info.getPropertyType() == null) {
            info.setPropertyType(type);
          }
        }
      }
    }
    final String prefix = propertySearchRoot != null ? propertySearchRoot + "." : "";
    for (Map.Entry<String, PropertyInfo> entry : propertyInfoMap.entrySet()) {
      properties.put(prefix + entry.getKey(), entry.getValue());
    }
    return properties;
  }

  public static Collection<BoundMetaData> getAllBoundMetaDataFromClass(PsiElement element) {
    final PsiClass psiClass;
    if (element instanceof PsiClass) {
      psiClass = (PsiClass) element;
    }
    else {
      psiClass = PsiUtil.getTopLevelClass(element);
    }

    List<BoundMetaData> boundMetaDatas = new ArrayList<BoundMetaData>();
    for (AnnotationSearchResult result : Util.findAllAnnotatedElements(psiClass, Types.BOUND)) {
      boundMetaDatas.add(getBoundMetaData(result.getOwningElement()));
    }
    return boundMetaDatas;
  }

  public static BoundMetaData getBoundMetaData(PsiElement element) {
    return new BoundMetaData(Util.getImmediateOwnerElement(element));
  }

  public static TemplateBindingMetaData getTemplateBindingMetaData(final PsiElement element) {
    return Util.getOrCreateCache(TEMPLATE_BINDING_META_DATA_KEY, element, new CacheProvider<TemplateBindingMetaData>() {
      @Override
      public TemplateBindingMetaData provide() {
        final PsiClass topLevelClass = PsiUtil.getTopLevelClass(element);
        return new TemplateBindingMetaData(topLevelClass);
      }

      @Override
      public boolean isCacheValid(TemplateBindingMetaData templateBindingMetaData) {
        return templateBindingMetaData.isCacheValid();
      }
    });
  }

  public static PsiClass getBeanPropertyType(PsiClass type, String property) {
    if (type == null) {
      return null;
    }
    final PropertyInfo beanPropertyInfo = getBeanPropertyInfo(type, property);
    if (beanPropertyInfo == null) {
      return null;
    }
    return beanPropertyInfo.getPropertyType();
  }

  public static PropertyInfo getBeanPropertyInfo(PsiClass type, String property) {
    if (type == null) return null;

    final String getMethod = "get" + property;
    final String isMethod = "is" + property;

    for (PsiMethod method : type.getAllMethods()) {
      if (method.getModifierList().hasModifierProperty("public")) {
        if ((getMethod.equalsIgnoreCase(method.getName()) || isMethod.equalsIgnoreCase(method.getName()))
            && method.getParameterList().getParameters().length == 0) {

          PropertyInfo propertyInfo = new PropertyInfo();
          propertyInfo.setGetterElement(method);
          propertyInfo.setPropertyName(property);
          propertyInfo.setPropertyType(getPsiClassFromType(type.getProject(), method.getReturnType()));

          return propertyInfo;
        }
      }
    }
    return null;
  }


  public static PsiClass getPsiClassFromType(Project project, PsiType type) {
    String typeName = Util.getErasedCanonicalText(type.getCanonicalText());

    if ("int".equals(typeName)) {
      typeName = Integer.class.getName();
    }
    else if ("boolean".equals(typeName)) {
      typeName = Boolean.class.getName();
    }
    else if ("long".equals(typeName)) {
      typeName = Long.class.getName();
    }
    else if ("double".equals(typeName)) {
      typeName = Double.class.getName();
    }
    else if ("float".equals(typeName)) {
      typeName = Float.class.getName();
    }
    else if ("short".equals(typeName)) {
      typeName = Short.class.getName();
    }
    else if ("char".equals(typeName)) {
      typeName = Character.class.getName();
    }
    else if ("byte".equals(typeName)) {
      typeName = Byte.class.getName();
    }

    return JavaPsiFacade.getInstance(project).findClass(typeName, GlobalSearchScope.allScope(project));
  }

  private static PropertyInfo getOrCreatePropertyInfo(Map<String, PropertyInfo> map, String property) {
    PropertyInfo info = map.get(property);
    if (info == null) {
      map.put(property, info = new PropertyInfo());
      info.setPropertyName(property);
    }
    return info;
  }

  public DataBindUtil() {
  }

  @SuppressWarnings("ConstantConditions")
  public static BindabilityValidation typeIsBindableToWidget(PsiClass bindingType,
                                                             PsiClass widgetType,
                                                             ConvertibilityMetaData convertibilityMetaData) {
    if (bindingType == null) return new BindabilityValidation(false);

    BindabilityValidation validation = new BindabilityValidation();
    final PsiClassType[] superTypes = widgetType.getSuperTypes();
    validation.setValid(true);

    Stack<PsiClassType> toSearch = new Stack<PsiClassType>();
    for (PsiClassType type : superTypes) {
      toSearch.push(type);
    }

    while (!toSearch.isEmpty()) {
      PsiClassType type = toSearch.pop();
      for (PsiType psiType : type.getSuperTypes()) {
        if (psiType instanceof PsiClassType) {
          PsiClassType t = (PsiClassType) psiType;
          if (!t.getCanonicalText().equals("java.lang.Object")) {
            toSearch.push(t);
          }
        }
      }

      if (type.getCanonicalText().startsWith(Types.GWT_TAKES_VALUE)) {
        PsiClass typeParm = Util.getErasedTypeParam(bindingType.getProject(), type.getCanonicalText());

        if (typeParm != null) {
          if (!Util.typeIsAssignableFrom(typeParm, bindingType.getQualifiedName())
              && !convertibilityMetaData.canConvert(bindingType, typeParm)) {
            validation.setValid(false);
            validation.setExpectedWidgetType(typeParm.getQualifiedName());
          }
        }
        else {
          validation.setValid(false);
          validation.setExpectedWidgetType("<invalid>");
        }
        break;
      }
      else if (!validation.isValid() && Util.typeIsAssignableFrom(widgetType, Types.GWT_HAS_TEXT)) {
        validation.setValid(true);
        break;
      }
    }


    return validation;
  }

  public static String getPropertyFromAccessor(String s) {
    char[] c = s.toCharArray();
    char[] chars;

    if (c.length > 3 && c[1] == 'e' && c[2] == 't') {
      chars = new char[c.length - 3];

      if (c[0] == 'g' || c[0] == 's') {
        if (c[3] < 'a') {
          chars[0] = (char) (c[3] + CASE_OFFSET);
        }
        else {
          chars[0] = c[3];
        }

        for (int i = 1; i < chars.length; i++) {
          chars[i] = c[i + 3];
        }

        return new String(chars);
      }
      else {
        return s;
      }
    }
    else if (c.length > 2 && c[0] == 'i' && c[1] == 's') {
      chars = new char[c.length - 2];

      if (c[2] < 'a') {
        chars[0] = (char) (c[2] + CASE_OFFSET);
      }
      else {
        chars[0] = c[2];
      }

      for (int i = 1; i < chars.length; i++) {
        chars[i] = c[i + 2];
      }

      return new String(chars);
    }
    return s;
  }


  public static ConvertibilityMetaData getConvertibilityMetaData(PsiAnnotation boundAnnotation) {
    final ConvertibilityMetaData cm = new ConvertibilityMetaData();

    final List<String> parms = Util.getErasedTypeParamsCanonicalText(Util.getAttributeValue(boundAnnotation, "converter", DefaultPolicy.NULL));

    if (!parms.isEmpty()) {
      final String converter = parms.get(0);
      final Project project = boundAnnotation.getProject();
      final JavaPsiFacade instance = JavaPsiFacade.getInstance(project);
      final PsiClass psiClass
          = instance.findClass(converter, GlobalSearchScope.allScope(project));

      if (psiClass == null) {
        return cm;
      }

      final SuperTypeInfo superTypeInfo = Util.getTypeInformation(psiClass, Types.CONVERTER);
      final ConvertibilityMetaData metaData = new ConvertibilityMetaData();
      if (superTypeInfo != null) {
        metaData.addConversionRule(
            instance.findClass(superTypeInfo.getTypeParms().get(0), GlobalSearchScope.allScope(project)),
            instance.findClass(superTypeInfo.getTypeParms().get(1), GlobalSearchScope.allScope(project))
        );
      }

      return metaData;
    }
    return cm;
  }

  public static Set<String> getConfiguredBindableTypes(Project project) {
    final PsiFile[] properties = Util.getAllErraiAppProperties(project);
    final Set<String> bindableTypes = new HashSet<String>();

    for (PsiFile file : properties) {
      final List<IProperty> allProperties = PropertiesUtil.findAllProperties(project, PropertiesUtil.getResourceBundle(file), "errai.ui.bindableTypes");
      for (IProperty property : allProperties) {
        final String value = property.getValue();
        if (value != null) {
          for (String s : value.split("\\s+")) {
            bindableTypes.add(s.trim());
          }
        }
      }
    }

    return bindableTypes;
  }
}
