#
# Manually run this on a vanilla R installation to prepare the R installation directory
# for being zipped up as R-3.0.3.zip for SEE.
#
# Note that this set-up process works best if this vanilla R installation is in user-space
# (suggest copying a fresh installation under usual location of "Program Files", into user-space).
#

mainReposURL <- 'http://www.stats.bris.ac.uk/R';

ddmore.pkg.dep=c("bitops", "brew", "digest", "RCurl", "rjson", "roxygen2", "stringr", "XML");
print('installing TEL R package dependencies');
install.packages(ddmore.pkg.dep, repos=mainReposURL);

simulx.dep=c("Rcpp", "ggplot2", "gridExtra", "reshape", "reshape2");
print('installing mlxR (simulx front-end) dependencies');
install.packages(simulx.dep, repos=mainReposURL);

print('installing mlxR (simulx front-end)');
# Note that these instructions are specific to v3.0.3 of R!
install.packages("mlxR", repos=mainReposURL, type="source")
library("mlxR")
tmpLibDir <- file.path(tempdir(), "temp-library")
if (!file.exists(tmpLibDir)) { # Just in case the setup script is being re-run
	dir.create(tmpLibDir)
}
install.packages('devtools', repos=mainReposURL, lib=tmpLibDir) # This library only required for downloading/installing the mlxR library hence we don't want to install it properly
library("httr", lib.loc=tmpLibDir)
library("devtools", lib.loc=tmpLibDir)
install_github("MarcLavielle/mlxR")
library("mlxR")

print('installing xpose dependencies');
install.packages("xpose4", repos=mainReposURL);

print('installing tables library, convenient to be included for Product 3 (SEE v1.0.5)');
install.packages("tables", repos=mainReposURL);

print("installing knitr library, it was suggested it may be useful")
install.packages("knitr", repos=mainReposURL)

# we don't want to run statet, it requires java
lapply(c(ddmore.pkg.dep, simulx.dep, "xpose4"), function (x) library(x, character.only=TRUE) )
