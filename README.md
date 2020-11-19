# Replication Workflow Process 

Replication workflow process step that publishes the page or asset and its references.  This type of publishing is available in the AEM UI as "Quick Publish".

## Usage Instructions
1. Install the core project bundle to AEM
2. Go to a workflow such as the "Request for Activation" out-of-the-box one and change the workflow process step from "Activate Page" to "Activate Page w/ References"
3. Save
4. When the workflow runs, now it will activate the references in addition to the page

## Modules

The main parts of the template are:

* core: contains the workflow process.

## How to build

To build all the modules run in the project root directory the following command with Maven 3:

    mvn clean install

If you have a running Granite instance you can build and package the whole project and deploy into Granite with  

    mvn clean install -Pfull

In order to only build and deploy one bundle run in the core or components directory:

    mvn clean install -Pbundle

You need to configure the Adobe Maven repository in your Maven settings:

		<profile>
			<id>adobe-public</id>
			<activation>
				<activeByDefault>false</activeByDefault>
			</activation>
			<properties>
				<releaseRepository-Id>adobe-public-releases</releaseRepository-Id>
				<releaseRepository-Name>Adobe Public Releases</releaseRepository-Name>
				<releaseRepository-URL>http://repo.adobe.com/nexus/content/groups/public</releaseRepository-URL>
			</properties>
			<repositories>
				<repository>
					<id>adobe-public-releases</id>
					<name>Adobe Basel Public Repository</name>
					<url>http://repo.adobe.com/nexus/content/groups/public</url>
					<releases>
						<enabled>true</enabled>
						<updatePolicy>never</updatePolicy>
					</releases>
					<snapshots>
						<enabled>false</enabled>
					</snapshots>
				</repository>
			</repositories>
			<pluginRepositories>
				<pluginRepository>
					<id>adobe-public-releases</id>
					<name>Adobe Basel Public Repository</name>
					<url>http://repo.adobe.com/nexus/content/groups/public</url>
					<releases>
						<enabled>true</enabled>
						<updatePolicy>never</updatePolicy>
					</releases>
					<snapshots>
						<enabled>false</enabled>
					</snapshots>
				</pluginRepository>

