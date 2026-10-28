import com.android.aapt.Resources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static java.util.Map.entry;

/** Official AAPT protobuf bindings supplied by the pinned bundletool JAR. */
public final class ProtoPersonalizer {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    // Reviewed template vocabulary, with Android framework IDs (android.R.attr;
    // compileSdkVersion/Codename are AAPT's internal framework attributes).
    // Android consumes these IDs, not just the printed XML names. Fail closed
    // when a newer template introduces an unreviewed attribute or any alias.
    private static final Map<String, Integer> ANDROID_ATTRIBUTES = Map.ofEntries(
        entry("theme", 0x01010000), entry("label", 0x01010001),
        entry("icon", 0x01010002), entry("name", 0x01010003),
        entry("permission", 0x01010006), entry("protectionLevel", 0x01010009),
        entry("enabled", 0x0101000e), entry("exported", 0x01010010),
        entry("process", 0x01010011), entry("taskAffinity", 0x01010012),
        entry("excludeFromRecents", 0x01010017), entry("authorities", 0x01010018),
        entry("launchMode", 0x0101001d), entry("screenOrientation", 0x0101001e),
        entry("configChanges", 0x0101001f), entry("value", 0x01010024),
        entry("resource", 0x01010025), entry("minSdkVersion", 0x0101020c),
        entry("versionCode", 0x0101021b), entry("versionName", 0x0101021c),
        entry("windowSoftInputMode", 0x0101022b), entry("targetSdkVersion", 0x01010270),
        entry("allowBackup", 0x01010280), entry("required", 0x0101028e),
        entry("supportsRtl", 0x010103af), entry("extractNativeLibs", 0x010104ea),
        entry("directBootAware", 0x01010505), entry("networkSecurityConfig", 0x01010527),
        entry("roundIcon", 0x0101052c), entry("appCategory", 0x01010545), entry("compileSdkVersion", 0x01010572),
        entry("compileSdkVersionCodename", 0x01010573), entry("appComponentFactory", 0x0101057a),
        entry("foregroundServiceType", 0x01010599), entry("dataExtractionRules", 0x0101063e)
    );
    private static String oldId, newId;
    private static int version;
    private static final Set<String> seen = new HashSet<>();
    private static int applications;
    private static final Set<String> COMPONENTS = Set.of("application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation");
    private static final Set<String> ROOT_CHILDREN = Set.of("uses-sdk", "uses-permission", "uses-permission-sdk-23", "uses-feature", "permission", "queries", "application", "supports-screens", "compatible-screens", "supports-gl-texture");
    private static final Set<String> APP_CHILDREN = Set.of("activity", "activity-alias", "service", "receiver", "provider", "meta-data", "uses-library", "uses-native-library", "profileable", "property");

    private static String attribute(Resources.XmlElement.Builder e, String name) {
        for (var a : e.getAttributeList())
            if (a.getNamespaceUri().equals(ANDROID) && a.getName().equals(name)) return a.getValue();
        return "";
    }

    private static void noOldIdentity(byte[] bytes) {
        String raw = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        require(!raw.contains(oldId));
        for (var charset : new java.nio.charset.Charset[] {java.nio.charset.StandardCharsets.UTF_16LE, java.nio.charset.StandardCharsets.UTF_16BE})
            require(!raw.contains(new String(oldId.getBytes(charset), java.nio.charset.StandardCharsets.ISO_8859_1)));
    }

    private static boolean knownElement(String parent, String name) {
        if (parent.isEmpty()) return name.equals("manifest");
        if (parent.equals("/manifest")) return ROOT_CHILDREN.contains(name);
        if (parent.equals("/manifest/application")) return APP_CHILDREN.contains(name);
        if (parent.equals("/manifest/queries")) return Set.of("intent", "package", "provider").contains(name);
        if (parent.equals("/manifest/queries/intent")) return Set.of("action", "category", "data").contains(name);
        if (parent.equals("/manifest/compatible-screens")) return name.equals("screen");
        if (parent.startsWith("/manifest/application/")) {
            String tail = parent.substring("/manifest/application/".length());
            if (Set.of("activity", "activity-alias", "service", "receiver", "provider").contains(tail))
                return Set.of("intent-filter", "meta-data", "property", "grant-uri-permission", "path-permission", "layout").contains(name);
            if (tail.endsWith("/intent-filter")) return Set.of("action", "category", "data").contains(name);
        }
        return false;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Unsupported template");
    }

    private static Resources.XmlNode rewrite(Resources.XmlNode node, String parent) {
        if (!node.hasElement()) { require(node.getText().isBlank()); return node; }
        var e = node.getElement().toBuilder();
        require(e.getNamespaceUri().isEmpty() && knownElement(parent, e.getName()));
        String path = parent + "/" + e.getName();
        if (path.equals("/manifest/application")) {
            applications++;
            require(attribute(e, "name").equals("com.openautolink.app.OalApplication"));
        }
        Set<String> attributes = new HashSet<>();
        if (path.equals("/manifest/permission")) {
            require(attribute(e, "name").equals(oldId + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"));
            require(Set.of("2", "0x00000002", "signature").contains(attribute(e, "protectionLevel")));
        }
        for (int i = 0; i < e.getAttributeCount(); i++) {
            var a = e.getAttribute(i);
            require(attributes.add(a.getNamespaceUri() + "@" + a.getName()));
            if (a.getNamespaceUri().equals(ANDROID)) {
                require(ANDROID_ATTRIBUTES.containsKey(a.getName())
                    && a.getResourceId() == ANDROID_ATTRIBUTES.get(a.getName()));
            } else {
                require(a.getNamespaceUri().isEmpty() && a.getResourceId() == 0
                    && path.equals("/manifest")
                    && Set.of("package", "platformBuildVersionCode", "platformBuildVersionName").contains(a.getName()));
            }
            require(!Set.of("split", "configForSplit", "isFeatureSplit").contains(a.getName()));
            if (a.hasCompiledItem()) {
                var item = a.getCompiledItem();
                if (item.hasStr()) require(item.getStr().getValue().equals(a.getValue()));
                if (item.hasRawStr()) require(item.getRawStr().getValue().equals(a.getValue()));
            }
            if (a.getNamespaceUri().equals(ANDROID)) {
                // Preserve only the reviewed AAOS media classification, never rewrite it.
                if (a.getName().equals("appCategory")) {
                    require(path.equals("/manifest/application") && a.getValue().equals("audio")
                        && a.hasCompiledItem() && a.getCompiledItem().hasPrim());
                    var prim = a.getCompiledItem().getPrim();
                    require(prim.getOneofValueCase() == Resources.Primitive.OneofValueCase.INT_DECIMAL_VALUE
                        && prim.getIntDecimalValue() == 1);
                }
                // Protection/runtime routing must not disagree with printed XML,
                // or resolve through an unreviewed resource reference.
                if (Set.of("name", "permission", "process", "taskAffinity", "authorities", "appComponentFactory").contains(a.getName())
                        && a.hasCompiledItem())
                    require(a.getCompiledItem().hasStr() || a.getCompiledItem().hasRawStr());
                if (Set.of("enabled", "exported", "directBootAware", "allowBackup", "required", "supportsRtl", "extractNativeLibs", "excludeFromRecents").contains(a.getName())) {
                    require(a.hasCompiledItem() && a.getCompiledItem().hasPrim());
                    var prim = a.getCompiledItem().getPrim();
                    require(prim.getOneofValueCase() == Resources.Primitive.OneofValueCase.BOOLEAN_VALUE
                        && Boolean.toString(prim.getBooleanValue()).equals(a.getValue()));
                }
                if (Set.of("minSdkVersion", "targetSdkVersion").contains(a.getName())) {
                    require(a.hasCompiledItem() && a.getCompiledItem().hasPrim());
                    var prim = a.getCompiledItem().getPrim();
                    require(prim.getOneofValueCase() == Resources.Primitive.OneofValueCase.INT_DECIMAL_VALUE
                        && prim.getIntDecimalValue() > 0
                        && Integer.toString(prim.getIntDecimalValue()).equals(a.getValue()));
                }
                boolean className = (a.getName().equals("name") && COMPONENTS.contains(e.getName()))
                    || Set.of("targetActivity", "parentActivityName", "appComponentFactory", "backupAgent", "manageSpaceActivity", "zygotePreloadName").contains(a.getName());
                if (className) require(a.getValue().matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"));
                if (className && a.hasCompiledItem())
                    require(a.getCompiledItem().hasStr() || a.getCompiledItem().hasRawStr());
                require(!Set.of("sharedUserId", "sharedUserMaxSdkVersion", "versionCodeMajor", "isFeatureSplit", "targetPackage").contains(a.getName()));
                if (a.getName().equals("taskAffinity"))
                    require(path.equals("/manifest/application/activity") && attribute(e, "name").equals("androidx.car.app.activity.CarAppActivity") && a.getValue().equals(oldId + ".templates"));
                if (a.getName().equals("authorities") && path.startsWith("/manifest/application/"))
                    require(path.equals("/manifest/application/provider") && attribute(e, "name").equals("androidx.startup.InitializationProvider") && a.getValue().equals(oldId + ".androidx-startup"));
            }
            if (path.equals("/manifest/permission")) {
                if (a.getName().equals("protectionLevel") && a.hasCompiledItem()) {
                    var prim = a.getCompiledItem().getPrim();
                    require((prim.getOneofValueCase() == Resources.Primitive.OneofValueCase.INT_HEXADECIMAL_VALUE && prim.getIntHexadecimalValue() == 2)
                        || (prim.getOneofValueCase() == Resources.Primitive.OneofValueCase.INT_DECIMAL_VALUE && prim.getIntDecimalValue() == 2));
                }
            }
            String key = path + "@" + a.getName();
            String replacement = null;
            boolean numeric = false;
            if (key.equals("/manifest@package") && a.getNamespaceUri().isEmpty()) {
                require(a.getValue().equals(oldId));
                replacement = newId;
            } else if (a.getNamespaceUri().equals(ANDROID)) {
                if (key.equals("/manifest@versionCode")) {
                    numeric = true;
                    replacement = Integer.toString(version);
                } else if ((key.equals("/manifest/permission@name") || key.equals("/manifest/uses-permission@name"))
                        && a.getValue().equals(oldId + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")) {
                    replacement = newId + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
                } else if (key.equals("/manifest/application/activity@taskAffinity") && a.getValue().equals(oldId + ".templates")) {
                    replacement = newId + ".templates";
                } else if (key.equals("/manifest/application/provider@authorities") && a.getValue().equals(oldId + ".androidx-startup")) {
                    replacement = newId + ".androidx-startup";
                }
            }
            if (replacement != null) {
                require(seen.add(key));
                var edited = a.toBuilder().setValue(replacement);
                if (numeric) {
                    require(a.hasCompiledItem() && a.getCompiledItem().hasPrim());
                    var prim = a.getCompiledItem().getPrim();
                    require(prim.getOneofValueCase() == Resources.Primitive.OneofValueCase.INT_DECIMAL_VALUE
                        && Integer.toString(prim.getIntDecimalValue()).equals(a.getValue()) && prim.getIntDecimalValue() > 0);
                    edited.setCompiledItem(a.getCompiledItem().toBuilder().setPrim(
                        a.getCompiledItem().getPrim().toBuilder().setIntDecimalValue(version)));
                } else if (a.hasCompiledItem()) {
                    var item = a.getCompiledItem().toBuilder();
                    if (item.hasStr()) item.setStr(item.getStr().toBuilder().setValue(replacement));
                    else if (item.hasRawStr()) item.setRawStr(item.getRawStr().toBuilder().setValue(replacement));
                    else throw new IllegalArgumentException("Unsupported template");
                    edited.setCompiledItem(item);
                }
                e.setAttribute(i, edited);
            }
        }
        for (int i = 0; i < e.getChildCount(); i++) e.setChild(i, rewrite(e.getChild(i), path));
        return node.toBuilder().setElement(e).build();
    }

    private static void transform(String[] args) throws Exception {
        require(args.length == 5);
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        oldId = args[2]; newId = args[3]; version = Integer.parseInt(args[4]);
        var original = Resources.XmlNode.parseFrom(Files.readAllBytes(input.resolve("manifest.pb")));
        var changed = rewrite(original, "");
        require(seen.size() == 6 && applications == 1);
        var table = Resources.ResourceTable.parseFrom(Files.readAllBytes(input.resolve("resources.pb")));
        require(table.getPackageCount() == 1 && table.getPackage(0).getPackageName().equals(oldId)
            && table.getPackage(0).hasPackageId() && table.getPackage(0).getPackageId().getId() == 127);
        var edited = table.toBuilder().setPackage(0, table.getPackage(0).toBuilder().setPackageName(newId)).build();
        noOldIdentity(changed.toByteArray());
        noOldIdentity(edited.toByteArray());
        Files.write(output.resolve("manifest.pb"), changed.toByteArray());
        Files.write(output.resolve("resources.pb"), edited.toByteArray());
    }

    public static void main(String[] args) {
        try { transform(args); }
        catch (Exception | StackOverflowError failure) {
            // Never print protobuf values, input paths or arbitrary exception text.
            System.err.println("OAL_PERSONALIZE_UNSUPPORTED_PROTO");
            System.exit(2);
        }
    }
}
