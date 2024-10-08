#!/usr/local/bin/groovy
import groovy.transform.WithWriteLock
//@GrabResolver(name='restlet.org', root='http://maven.restlet.org')
@Grab('org.codehaus.gpars:gpars:1.2.1')
@Grab('org.codehaus.groovy.modules.http-builder:http-builder')
@Grab('commons-io:commons-io:2.11.0')
import groovyx.gpars.GParsPool
import groovyx.net.http.RESTClient
import org.apache.commons.io.FileUtils

import java.security.SecureRandom

// Run the generator
new GenerateChef().init(args)

class GenerateChef extends Generator {
    public static final String OUTPUT_PREFIX = "##CHEF##"
    public static final String ADD_PREFIX = "ADD"
    public static final int NUM_OF_WORKERS = 8
    // User input
    def artifactoryUrl, artifactoryUser, artifactoryPassword, repoKey, packagesAmount, packageNumberStart, maxFileSize, minFileSize
    synchronized def passed = true

    /**
     * Generates chef packages and deploys them to Artifactory
     */
    @WithWriteLock
    boolean generate() {
        SecureRandom random = new SecureRandom()
        String artifactFolder = "test_cookbook"
        File outDir = new File("generated")
        outDir.mkdirs()
        println """What we are going to do?
We are going to build  $packagesAmount  package(s). Package size between $minFileSize and $maxFileSize bytes"""

        ['jfrog', 'rt', 'c', "--interactive=false", "--url=${artifactoryUrl}", "--user=${artifactoryUser}", "--password=${artifactoryPassword}", 'art'].execute()

        (packageNumberStart..packagesAmount).each { version ->
            String artifactName = HelperTools.generateString(9)
            sleep(1)
            int fileSize = (minFileSize == maxFileSize) ? minFileSize : Math.abs(random.nextLong() % (maxFileSize - minFileSize)) + minFileSize
            String extraFileName = "extra_file.bin"
            File addFile = new File(artifactFolder, extraFileName)
            HelperTools.createBinFile(addFile, fileSize)
            println("$OUTPUT_PREFIX $ADD_PREFIX $repoKey/${artifactFolder}/${extraFileName} - ${fileSize / 1000} Kb")

            // metadata.json
            String metadataName = "metadata.json"
            File metadataFile = new File(artifactFolder, metadataName)
            metadataFile.withWriter { w ->
                w << generateJSON(version as String, artifactName as String)
            }
            println("$OUTPUT_PREFIX $ADD_PREFIX $repoKey/${artifactFolder}/${metadataName} ${HelperTools.getFileSha1(metadataFile)}")

            // create package dir and copy files there
            File packageDir = new File(artifactName)
            packageDir.mkdirs()
            new File(artifactFolder).listFiles().each {
                it.isDirectory() ? FileUtils.copyDirectoryToDirectory(it, packageDir) : FileUtils.copyFileToDirectory(it, packageDir)
            }

            // create tgz
            String packageLocation = "$outDir/${artifactName}.tgz"
            ["tar", "czvf", "$packageLocation", "${packageDir.path}",].execute()
            sleep(250)
            addFile.delete()
            packageDir.deleteDir()
        }

        String cmd = "jfrog rt u " +
                "${outDir}/*.tgz " +
                "$repoKey/ " +
                "--server-id=art " +
                "--threads=15"
        println cmd
        passed &= HelperTools.executeCommandAndPrint(cmd) == 0
//         save file names to separate file
        File uploadedFilesList = new File("uploadedFiles")
        outDir.eachFile {
            uploadedFilesList << "${it.name}\n"
        }

        FileUtils.deleteDirectory(outDir)
        return passed
    }

    /**
     * Reads the output of the generate method and verifies that the data exists
     * @param filePath The output of the generator method (stored in a file)
     * @return True if the verification succeeded
     */
    boolean verify(def filePath) {
        def toVerify = []
        // Create a map of artifact path and sha1s that we will use to verify
        (new File(filePath)).eachLine { String line ->
            if (line.startsWith("$OUTPUT_PREFIX $ADD_PREFIX ")) {
                toVerify << ["name": line.split()[2], "sha1": line.split()[3]]
            }
        }

        GParsPool.withPool NUM_OF_WORKERS, {
            toVerify.eachParallel {
                // Not thread safe so build one per thread
                RESTClient rc = new RESTClient(artifactoryUrl)
                def base64 = "${artifactoryUser}:${artifactoryPassword}".bytes.encodeBase64().toString()
                rc.setHeaders([Authorization: "Basic ${base64}"])
                try {
                    def response = rc.head(path: "${artifactoryUrl}/${it.name}")
                    def headers = response.getHeaders()
                    if (headers && headers["X-Checksum-Sha1"]) {
                        def artSha1 = headers["X-Checksum-Sha1"].getValue()
                        if (artSha1 != it.sha1) {
                            System.err.println("Expected file ${it.name} with checksum ${it.sha1} " +
                                    "but it had sha1 ${artSha1}.")
                            passed = false
                        }
                    } else {
                        System.err.println("Expected file ${it.name} with checksum ${it.sha1} but did not find it.")
                        passed = false
                    }
                } catch (Exception e) {
                    System.err.println("Expected file ${it.name} with checksum ${it.sha1} but did not find it. " +
                            "Exception:  ${e.getMessage()}")
                    passed = false
                }
            }
        }
        return passed
    }

    /**
     * Reads the output of the generate method and cleans up
     * @param filePath The output of the generator method (stored in a file)
     * @return True if the cleanup succeeded
     */
    boolean cleanup(def filePath) {
        ['jfrog', 'rt', 'c', "--url=${artifactoryUrl}", "--user=${artifactoryUser}", "--password=${artifactoryPassword}", 'art'].execute().waitForOrKill(15000)
        def toDelete = []
        // Deletes the files created by this tool
        (new File(filePath)).eachLine { String line ->
            if (line.startsWith("$OUTPUT_PREFIX $ADD_PREFIX ")) {
                toDelete << line.split()[2]
            }
        }
        // Delete in batches
        GParsPool.withPool NUM_OF_WORKERS, {
            toDelete.eachParallel {
                String cmd = "jfrog rt delete --server-id=art --quiet $it"
                passed &= HelperTools.executeCommandAndPrint(cmd) == 0
            }
        }
        return passed
    }

    /**
     * Collects and verified the user provided input, transforming it as needed and erroring out if needed
     */
    void getInput() {
        // Load in the values from the user provided input
        artifactoryUrl = userInput.getUserInput("artifactory.url")
        if (artifactoryUrl.endsWith('/'))
            artifactoryUrl = artifactoryUrl.substring(0, artifactoryUrl.length() - 2)
        artifactoryUser = userInput.getUserInput("artifactory.user")
        artifactoryPassword = userInput.getUserInput("artifactory.password")
        repoKey = userInput.getUserInput("artifactory.repo")
        packagesAmount = userInput.getUserInput("package.number") as Integer
        packageNumberStart = userInput.getUserInput("package.number.start") as Integer
        maxFileSize = userInput.getUserInput("max.file.size") as Integer
        minFileSize = userInput.getUserInput("min.file.size") as Integer
    }

    @WithWriteLock
    private String generateJSON(String version, String name) {
        """{
    "name": "$name",
    "maintainer": "The Authors",
    "maintainer_email": "you@example.com",
    "license": "All Rights Reserved",
    "description": "Installs/Configures $name",
    "version": "$version",
    "chef_version": ">= 14.0",
    "platforms": {
    "centos": ">= 0.0.0",
    "debian": ">= 0.0.0",
    "fedora": ">= 0.0.0",
    "freebsd": ">= 0.0.0",
    "opensuse": ">= 0.0.0",
    "redhat": ">= 0.0.0",
    "suse": ">= 0.0.0",
    "ubuntu": ">= 0.0.0",
    "windows": ">= 0.0.0"
    }
}"""
    }

}
