# RPM spec template for BotMaker Studio, handed to jpackage via --resource-dir (see the `build-rpm`
# execution in ../../pom.xml). jpackage looks for this override under the name "<linux-package-name>.spec",
# so the file name must track <linuxPackageName>; rename both together or the override is silently ignored
# and you get the stock template back. The build log's "Using custom package resource [RPM spec file]" line
# is how you confirm it was picked up.
#
# FORKED FROM: JDK 21 (temurin), jdk.jpackage.jmod!/classes/jdk/jpackage/internal/resources/template.spec.
# It must stay matched to the JDK that runs jpackage — CI pins temurin 21 in both jobs (setup-java in
# .github/workflows/ci.yml). The placeholder set is NOT stable across releases: JDK 26's template adds a
# COMMON_SCRIPTS placeholder and rewrites the pre section's guard. If java-version moves, re-fork from that
# JDK's template and re-apply the delta below rather than editing this file in place.
#
# THE ENTIRE DELTA IS THE GUARD IN THE preun SECTION AT THE BOTTOM OF THIS FILE.
#
# Why: on an upgrade, rpm runs the NEW package's post section first and only then the OUTGOING package's
# preun. Upstream's preun unregisters the .desktop file unconditionally, so the sequence is "new package
# installs the menu entry" -> "old package deletes it", and BotMaker Studio disappears from the application
# search until you install a second time. Guarding on "$1" = 0 restricts the unregister to an actual
# removal, which is what the transaction argument is for ($1 is the number of instances that will remain:
# 0 on erase, 1 on upgrade). Reported by the maintainer, 2026-08; still unfixed upstream as of JDK 26.
#
# The "true;" prefix mirrors the idiom upstream already uses in the pre section: it keeps the block
# syntactically valid if DESKTOP_COMMANDS_UNINSTALL ever expands to nothing (it would, with
# <linuxShortcut>false</linuxShortcut>).

Summary: APPLICATION_SUMMARY
Name: APPLICATION_PACKAGE
Version: APPLICATION_VERSION
Release: APPLICATION_RELEASE
License: APPLICATION_LICENSE_TYPE
Vendor: APPLICATION_VENDOR

%if "xAPPLICATION_URL" != "x"
URL: APPLICATION_URL
%endif

%if "xAPPLICATION_PREFIX" != "x"
Prefix: APPLICATION_PREFIX
%endif

Provides: APPLICATION_PACKAGE

%if "xAPPLICATION_GROUP" != "x"
Group: APPLICATION_GROUP
%endif

Autoprov: 0
Autoreq: 0
%if "xPACKAGE_DEFAULT_DEPENDENCIES" != "x" || "xPACKAGE_CUSTOM_DEPENDENCIES" != "x"
Requires: PACKAGE_DEFAULT_DEPENDENCIES PACKAGE_CUSTOM_DEPENDENCIES
%endif

#comment line below to enable effective jar compression
#it could easily get your package size from 40 to 15Mb but
#build time will substantially increase and it may require unpack200/system java to install
%define __jar_repack %{nil}

# on RHEL we got unwanted improved debugging enhancements
%define _build_id_links none

%define package_filelist %{_builddir}/%{name}.files
%define app_filelist %{_builddir}/%{name}.app.files
%define filesystem_filelist %{_builddir}/%{name}.filesystem.files

%define default_filesystem / /opt /usr /usr/bin /usr/lib /usr/local /usr/local/bin /usr/local/lib

%description
APPLICATION_DESCRIPTION

%global __os_install_post %{nil}

%prep

%build

%install
rm -rf %{buildroot}
install -d -m 755 %{buildroot}APPLICATION_DIRECTORY
cp -r %{_sourcedir}APPLICATION_DIRECTORY/* %{buildroot}APPLICATION_DIRECTORY
if [ "$(echo %{_sourcedir}/lib/systemd/system/*.service)" != '%{_sourcedir}/lib/systemd/system/*.service' ]; then
  install -d -m 755 %{buildroot}/lib/systemd/system
  cp %{_sourcedir}/lib/systemd/system/*.service %{buildroot}/lib/systemd/system
fi
%if "xAPPLICATION_LICENSE_FILE" != "x"
  %define license_install_file %{_defaultlicensedir}/%{name}-%{version}/%{basename:APPLICATION_LICENSE_FILE}
  install -d -m 755 "%{buildroot}%{dirname:%{license_install_file}}"
  install -m 644 "APPLICATION_LICENSE_FILE" "%{buildroot}%{license_install_file}"
%endif
(cd %{buildroot} && find . -path ./lib/systemd -prune -o -type d -print) | sed -e 's/^\.//' -e '/^$/d' | sort > %{app_filelist}
{ rpm -ql filesystem || echo %{default_filesystem}; } | sort > %{filesystem_filelist}
comm -23 %{app_filelist} %{filesystem_filelist} > %{package_filelist}
sed -i -e 's/.*/%dir "&"/' %{package_filelist}
(cd %{buildroot} && find . -not -type d) | sed -e 's/^\.//' -e 's/.*/"&"/' >> %{package_filelist}
%if "xAPPLICATION_LICENSE_FILE" != "x"
  sed -i -e 's|"%{license_install_file}"||' -e '/^$/d' %{package_filelist}
%endif

%files -f %{package_filelist}
%if "xAPPLICATION_LICENSE_FILE" != "x"
  %license "%{license_install_file}"
%endif

%post
package_type=rpm
LAUNCHER_AS_SERVICE_SCRIPTS
DESKTOP_COMMANDS_INSTALL
LAUNCHER_AS_SERVICE_COMMANDS_INSTALL

%pre
package_type=rpm
LAUNCHER_AS_SERVICE_SCRIPTS
if [ "$1" = 2 ]; then
  true; LAUNCHER_AS_SERVICE_COMMANDS_UNINSTALL
fi

%preun
package_type=rpm
DESKTOP_SCRIPTS
LAUNCHER_AS_SERVICE_SCRIPTS
if [ "$1" = 0 ]; then
  true; DESKTOP_COMMANDS_UNINSTALL
fi
LAUNCHER_AS_SERVICE_COMMANDS_UNINSTALL

%clean
