# A module is loaded from a file the app did not compile against, and finds this contract by name
# through the app's class loader. Renaming any of it is an IncompatibleClassChangeError on the
# bike; stripping a method is a NoSuchMethodError. Keep the package as written on both sides.
-keep interface io.motohub.android.module.** { *; }
-keep class io.motohub.android.module.** { *; }
-keepnames class io.motohub.android.module.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,Signature,InnerClasses,EnclosingMethod
