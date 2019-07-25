package com.adobe.support.replication.impl;

import java.util.List;

import org.apache.sling.api.resource.ResourceResolver;

public interface ActivationReferenceSearch {

	List<String> search(String[] paths, ResourceResolver resolver);

}