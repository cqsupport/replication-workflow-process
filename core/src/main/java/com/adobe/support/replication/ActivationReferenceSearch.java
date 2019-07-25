package com.adobe.support.replication;

import java.util.Collection;
import java.util.List;

import org.apache.sling.api.resource.ResourceResolver;

public interface ActivationReferenceSearch {

	Collection<? extends String> search(String[] paths, ResourceResolver resolver);

}