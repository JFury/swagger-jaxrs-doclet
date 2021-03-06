package com.hypnoticocelot.jaxrs.doclet.parser;

import com.google.common.base.Predicate;

import com.hypnoticocelot.jaxrs.doclet.DocletOptions;
import com.hypnoticocelot.jaxrs.doclet.model.Model;
import com.hypnoticocelot.jaxrs.doclet.model.Property;
import com.hypnoticocelot.jaxrs.doclet.translator.Translator;
import com.sun.javadoc.*;

import java.util.*;

import static com.google.common.collect.Collections2.filter;

public class ApiModelParser {

    private final DocletOptions options;
    private final Translator translator;
    private final Type rootType;
    private final Set<Model> models;
    private final Map<String, String> fieldDesc;

    public ApiModelParser(DocletOptions options, Translator translator, Type rootType) {
        this.options = options;
        this.translator = translator;
        this.rootType = rootType;
        this.models = new LinkedHashSet<Model>();
        fieldDesc = new HashMap<String, String>();
    }

    public Set<Model> parse() {
        parseModel(rootType);
        return models;
    }

    private void parseModel(Type type) {
        ClassDoc classDoc = type.asClassDoc();
        if (!isParsableType(type) || alreadyStoredType(type)) {
            return;
        }

        Map<String, Type> types = findReferencedTypes(classDoc, new HashMap<String, Type>());
        Map<String, Property> elements = findReferencedElements(types);
        if (!elements.isEmpty()) {
            models.add(new Model(translator.typeName(type).value(), elements));
            parseNestedModels(types.values());
        }
    }

    private Map<String, Type> findReferencedTypes(ClassDoc classDoc, Map<String, Type> elements) {
        FieldDoc[] fieldDocs = classDoc.fields(false);
        if (fieldDocs != null) {
            for (FieldDoc field : fieldDocs) {
                String name = translator.fieldName(field).value();
                if (!field.isStatic() && name != null && !elements.containsKey(name)) {
                    elements.put(name, field.type());

                    StringBuilder buf = getFieldDescription(field);
                    if(buf.length()>0) {
                        fieldDesc.put(name, buf.toString());
                    }
                }
            }
        }

        MethodDoc[] methodDocs = classDoc.methods();
        if (methodDocs != null) {
            for (MethodDoc method : methodDocs) {
                String name = translator.methodName(method).value();
                if (name != null && !elements.containsKey(name)) {
                    elements.put(name, method.returnType());
                    if(method.commentText() != null) {
                        fieldDesc.put(name, method.commentText());
                    }
                }
            }
        }

        // Add superclass' properties to the model
        Type superClassType = classDoc.superclassType();
        if(superClassType != null) {
            if(isParsableType(superClassType) && !superClassType.qualifiedTypeName().startsWith("java."))
                findReferencedTypes(superClassType.asClassDoc(), elements);
        }

        return elements;
    }

    private StringBuilder getFieldDescription(MemberDoc field) {
        StringBuilder buf = new StringBuilder();

        if(field.commentText() != null) {
            buf.append(field.commentText());
        }

        String[] views;
        if((views=AnnotationHelper.getJsonViews(field.annotations()))!=null) {
           if(buf.length()>0)
               buf.append("; ");
            buf.append("VIEWS: ").append(Arrays.toString(views));
        }

        return buf;
    }

    private boolean isParsableType(Type type) {
        boolean isPrimitive = /* type.isPrimitive()? || */ AnnotationHelper.isPrimitive(type);
        boolean isJavaxType = type.qualifiedTypeName().startsWith("javax.");
        boolean isBaseObject = type.qualifiedTypeName().equals("java.lang.Object");
        boolean isTypeToTreatAsOpaque = options.getTypesToTreatAsOpaque().contains(type.qualifiedTypeName());
        ClassDoc classDoc = type.asClassDoc();

        return  !(isPrimitive || isJavaxType || isBaseObject || isTypeToTreatAsOpaque || classDoc == null );
    }

    private Map<String, Property> findReferencedElements(Map<String, Type> types) {
        Map<String, Property> elements = new HashMap<String, Property>();
        for (Map.Entry<String, Type> entry : types.entrySet()) {
            String typeName = entry.getKey();
            Type type = entry.getValue();
            ClassDoc typeClassDoc = type.asClassDoc();

            Type containerOf = parseParameterisedTypeOf(type);
            String containerTypeOf = containerOf == null ? null : translator.typeName(containerOf).value();

            String propertyName = translator.typeName(type).value();
            Property property;
            if (typeClassDoc != null && typeClassDoc.isEnum()) {
                property = new Property(typeClassDoc.enumConstants(), fieldDesc.get(typeName));
            } else {
                property = new Property(propertyName, fieldDesc.get(typeName), containerTypeOf);
            }
            elements.put(typeName, property);
        }
        return elements;
    }

    private void parseNestedModels(Collection<Type> types) {
        for (Type type : types) {
            parseModel(type);
            Type pt = parseParameterisedTypeOf(type);
            if (pt != null) {
                parseModel(pt);
            }
        }
    }

    private Type parseParameterisedTypeOf(Type type) {
        Type result = null;
        ParameterizedType pt = type.asParameterizedType();
        if (pt != null) {
            Type[] typeArgs = pt.typeArguments();
            if (typeArgs != null && typeArgs.length > 0) {
                result = type.simpleTypeName().endsWith("Map") && typeArgs.length > 1 ?  typeArgs[1] : typeArgs[0];
            }
        }
        return result;
    }

    private boolean alreadyStoredType(final Type type) {
        return filter(models, new Predicate<Model>() {
            @Override
            public boolean apply(Model model) {
                return model.getId().equals(translator.typeName(type).value());
            }
        }).size() > 0;
    }

}
