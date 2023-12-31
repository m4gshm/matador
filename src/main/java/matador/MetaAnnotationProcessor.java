package matador;

import io.jbock.javapoet.*;
import lombok.SneakyThrows;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.tools.JavaFileObject;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static io.jbock.javapoet.MethodSpec.methodBuilder;
import static io.jbock.javapoet.TypeSpec.classBuilder;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static javax.lang.model.SourceVersion.RELEASE_17;
import static javax.lang.model.element.Modifier.*;
import static matador.JavaPoetUtils.initMapByEntries;
import static matador.MetaBeanExtractor.getAggregatorName;

@SupportedAnnotationTypes("matador.Meta")
@SupportedSourceVersion(RELEASE_17)
public class MetaAnnotationProcessor extends AbstractProcessor {

    private static boolean isInheritMetamodel(TypeSpec typeSpec) {
        return typeSpec.superinterfaces.stream().anyMatch(s -> {
            var expected = ClassName.get(MetaModel.class);
            return s instanceof ParameterizedTypeName p ? p.rawType.equals(expected) : s.equals(expected);
        });
    }

    @SuppressWarnings("unchecked")
    private static MetaCustomizer<?> instantiate(Meta.Extend customizerInfo) {
        Class<? extends MetaCustomizer<?>> customizerClass;
        try {
            customizerClass = customizerInfo.value();
        } catch (MirroredTypeException e) {
            var typeMirror = e.getTypeMirror();
            if (typeMirror instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te) {
                customizerClass = (Class<? extends MetaCustomizer<?>>) load(te);
            } else {
                throw new MetaCustomizerException(typeMirror, e);
            }
        }

        var optsMap = Arrays.stream(customizerInfo.opts())
                .collect(toMap(Meta.Extend.Opt::key, Meta.Extend.Opt::value, (l, r) -> l));

        try {
            return customizerClass.getDeclaredConstructor(Map.class).newInstance(optsMap);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                 NoSuchMethodException e) {
            try {
                return customizerClass.getDeclaredConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                     NoSuchMethodException e2) {
                throw new MetaCustomizerException(customizerClass, e2);
            }
        }
    }

    private static Class<?> load(TypeElement te) {
        try {
            return MetaAnnotationProcessor.class.getClassLoader().loadClass(te.getQualifiedName().toString());
        } catch (ClassNotFoundException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    @SneakyThrows
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
        var elements = roundEnv.getElementsAnnotatedWith(Meta.class);
        var messager = this.processingEnv.getMessager();
        var extractor = new MetaBeanExtractor(messager);
        var beansByPackage = elements.stream()
                .map(e -> e instanceof TypeElement type ? type : null)
                .filter(Objects::nonNull)
                .map(extractor::getBean)
                .filter(Objects::nonNull)
                .collect(groupingBy(MetaBean::getPackageName));

        var metamodels = new HashMap<String, List<MetaBean>>();
        beansByPackage.forEach((pack, beans) -> {
            var packMetamodels = new ArrayList<MetaBean>();
            metamodels.put(pack, packMetamodels);
            for (var bean : beans) {
                var customizers = Arrays.stream(bean.getMeta().customizers())
                        .map((Meta.Extend customizerInfo) -> instantiate(customizerInfo))
                        .toList();

                var typeSpec = JavaPoetUtils.newTypeBuilder(messager, bean, customizers).build();
                if (isInheritMetamodel(typeSpec)) {
                    packMetamodels.add(bean);
                }

                var javaFileObject = JavaFile.builder(bean.getPackageName(), typeSpec).build().toJavaFileObject();
                writeFile(bean.getPackageName(), bean.getName(), javaFileObject);
            }
        });

        metamodels.forEach((pack, beans) -> {
            var aggregate = beans.stream().filter(b -> b.getMeta().aggregate()).toList();
            if (!aggregate.isEmpty()) {
                var mapParts = aggregate.stream().map(bean -> JavaPoetUtils.mapEntry(
                        bean.getType().getQualifiedName().toString() + ".class", "new " + bean.getName() + "()"
                ).toString()).toList();

                var typeName = getAggregatorName(pack);
                var typeSpec = classBuilder(typeName)
                        .addModifiers(PUBLIC, FINAL)
                        .addField(
                                FieldSpec.builder(ClassName.get("", typeName), "instance", PUBLIC, STATIC, FINAL)
                                        .initializer(
                                                CodeBlock.builder()
                                                        .addStatement("new " + typeName + "()")
                                                        .build()
                                        )
                                        .build()
                        )
                        .addField(JavaPoetUtils.mapField("metas", ClassName.get(Class.class), ClassName.get(MetaModel.class),
                                initMapByEntries(mapParts).build(), PRIVATE, FINAL))
                        .addMethod(methodBuilder("of")
                                .addModifiers(PUBLIC)
                                .addParameter(
                                        ParameterSpec.builder(ClassName.get(Class.class), "type").build()
                                )
                                .returns(MetaModel.class)
                                .addCode(
                                        CodeBlock.builder()
                                                .addStatement("return metas.get(type)")
                                                .build()
                                )
                                .build()
                        )
                        .build();
                writeFile(pack, typeName, JavaFile.builder(pack, typeSpec).build().toJavaFileObject());
            }
        });

        return true;
    }

    @SneakyThrows
    private void writeFile(String pack, String name, JavaFileObject javaFileObject) {
        var sourceFile = processingEnv.getFiler().createSourceFile((pack == null || pack.isEmpty() ? "" : pack + ".") + name);
        try (
                var out = new PrintWriter(sourceFile.openWriter());
                var reader = javaFileObject.openReader(true)
        ) {
            reader.transferTo(out);
        }
    }

}
