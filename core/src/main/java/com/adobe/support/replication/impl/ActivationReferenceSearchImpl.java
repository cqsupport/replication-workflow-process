package com.adobe.support.replication.impl;

import static org.apache.felix.scr.annotations.ReferenceCardinality.OPTIONAL_MULTIPLE;
import static org.apache.felix.scr.annotations.ReferencePolicy.DYNAMIC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.support.replication.ActivationReferenceSearch;
import com.day.cq.commons.jcr.JcrConstants;
import com.day.cq.replication.ReplicationStatus;
import com.day.cq.replication.Replicator;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.Template;
import com.day.cq.wcm.api.reference.Reference;
import com.day.cq.wcm.api.reference.ReferenceProvider;

/**
 * The <code>ReferenceSearchServlet</code> does search in all given resources
 * (usually pages) all references to assets, tags and configurations.
 */
@Component(metatype = false, immediate=true)
@Service(ActivationReferenceSearch.class)
public class ActivationReferenceSearchImpl implements ActivationReferenceSearch {
	private final Logger log = LoggerFactory.getLogger(ActivationReferenceSearchImpl.class);

	/** Node name of the structure node. For authored templates only. */
	public static final String NN_STRUCTURE = "structure";

	/**
	 * name of the tidy parameter
	 */
	public static final String TIDY = "tidy";

	/**
	 * name of the path parameter
	 */
	public static final String PATH = "path";

	@org.apache.felix.scr.annotations.Reference(referenceInterface = ReferenceProvider.class, cardinality = OPTIONAL_MULTIPLE, policy = DYNAMIC)
	private final List<ReferenceProvider> referenceProviders = new CopyOnWriteArrayList<ReferenceProvider>();

	/* (non-Javadoc)
	 * @see com.adobe.support.replication.impl.ActivationReferenceSearch#search(java.lang.String[], org.apache.sling.api.resource.ResourceResolver)
	 */
	public List<String> search(String[] paths, ResourceResolver resolver) {
		Set<Reference> allReferences = new TreeSet<Reference>(new Comparator<Reference>() {
			public int compare(Reference o1, Reference o2) {
				return o1.getResource().getPath().compareTo(o2.getResource().getPath());
			}
		});
		if (paths != null) {
			List<String> pathsList = new ArrayList<String>();

			Collections.addAll(pathsList, paths);

			// extend the path by other required resources
			pathsList.addAll(extendPaths(resolver, pathsList));

			// remove duplicates in the paths list
			removeDuplicates(pathsList);

			// search all refs that may be contained in one of the passed paths
			for (String path : pathsList) {
				if (path.length() > 0) {
					// get content node
					Resource r = resolver.getResource(path + "/" + JcrConstants.JCR_CONTENT);
					if (r == null) {
						r = resolver.getResource(path);
					}

					if (r == null) {
						continue;
					}

					for (ReferenceProvider referenceProvider : referenceProviders) {
						allReferences.addAll(referenceProvider.findReferences(r));
					}
				}
			}
		}

		ArrayList<String> references = new ArrayList<String>();
		for (Reference reference : allReferences) {

			boolean published = false;
			boolean outdated = false;
			ReplicationStatus replStatus = null;
			final Resource resource = reference.getResource();
			boolean canReplicate = canReplicate(reference.getResource().getPath(), resolver.adaptTo(Session.class));
			long lastPublished = 0;
			if (resource != null) {
				replStatus = resource.adaptTo(ReplicationStatus.class);
				if (replStatus != null) {
					published = replStatus.isDelivered() || replStatus.isActivated();
					if (published) {
						lastPublished = replStatus.getLastPublished().getTimeInMillis();
						outdated = lastPublished < reference.getLastModified();
					}
				}

				log.debug(
						"Considering reference at {} . Published: {}, outdated: {} ( lastPublished: {}, lastModified: {} )",
						new Object[] { reference.getResource().getPath(), published, outdated, new Date(lastPublished),
								new Date(reference.getLastModified()) });
			}

			if (!published || outdated) {
				references.add(reference.getResource().getPath());
			}

		}
		return references;

	}

	protected void bindReferenceProviders(ReferenceProvider referenceProvider) {

		referenceProviders.add(referenceProvider);
	}

	protected void unbindReferenceProviders(ReferenceProvider referenceProvider) {

		referenceProviders.remove(referenceProvider);
	}

	private static boolean canReplicate(String path, Session session) {
		try {
			AccessControlManager acMgr = session.getAccessControlManager();
			return session.getAccessControlManager().hasPrivileges(path,
					new Privilege[] { acMgr.privilegeFromName(Replicator.REPLICATE_PRIVILEGE) });
		} catch (RepositoryException e) {
			return false;
		}
	}

	private List<String> extendPaths(ResourceResolver resolver, List<String> paths) {
		List<String> list = new ArrayList<String>();

		// check if any path ia based on a structured template
		if (paths != null) {
			for (String path : paths) {
				if (path.length() > 0) {
					// get resource
					Resource r = resolver.getResource(path);
					if (r == null) {
						continue;
					}

					// add structured templates
					// if a page is based on a structured template we have to activate the template
					// and its
					// references too.
					list.addAll(getTemplatePaths(r));
				}
			}
		}
		return list;
	}

	private List<String> getTemplatePaths(Resource r) {
		List<String> paths = new ArrayList<String>();

		Template t = r.adaptTo(Template.class);
		Page p = r.adaptTo(Page.class);
		if (t == null && p == null) {
			// nothing to do
			return paths;
		}

		// resource is a page
		if (p != null) {
			t = p.getTemplate();
		}

		// resource is or is based on a template
		if (t != null && t.hasStructureSupport()) {
			// template itself
			paths.add(t.getPath());

			Resource tr = t.adaptTo(Resource.class);
			// structure resource
			Resource sr = tr.getChild(NN_STRUCTURE);
			if (sr != null) {
				paths.add(sr.getPath());
			}
		}
		return paths;
	}

	private void removeDuplicates(List<String> pathsList) {
		int index = 0;

		while (index < pathsList.size() - 1) {
			String path = pathsList.get(index);

			List<String> tail = pathsList.subList(index + 1, pathsList.size());

			while (tail.contains(path)) {
				tail.remove(path);
			}
			index++;
		}
	}

}
