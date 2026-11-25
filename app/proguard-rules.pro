# Android has no JMX. ManuscriptGit disables JGit's optional monitoring before opening a repository.
-dontwarn java.lang.management.ManagementFactory
-dontwarn javax.management.**
