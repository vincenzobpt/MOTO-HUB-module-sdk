# Shared module code

Source the modules in this repository compile in alongside their own, through
`sourceSets["main"].java.srcDir("../common/src/main/java")` in their build file. Not a library:
each module dexes its own copy, under its own class loader, so nothing here has to be in the app.
