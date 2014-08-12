package org.alfresco.consulting.indexer.dao;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.consulting.indexer.entities.NodeBatchLoadEntity;
import org.alfresco.consulting.indexer.entities.NodeEntity;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.site.SiteService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.Pair;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ibatis.session.RowBounds;
import org.mybatis.spring.SqlSessionTemplate;

public class IndexingDaoImpl
{

    private static final String SELECT_NODES_BY_ACLS = "alfresco.index.select_NodeIndexesByAclChangesetId";
    private static final String SELECT_NODES_BY_TXNS = "alfresco.index.select_NodeIndexesByTransactionId";
    private static final String SELECT_NODES_BY_UUID = "alfresco.index.select_NodeIndexesByUuid";

    protected static final Log logger = LogFactory.getLog(IndexingDaoImpl.class);

    private SqlSessionTemplate template;
    private SiteService siteService;
    private NodeService nodeService;
    
    private Set<String> allowedTypes;
    private Set<String> excludedNameExtension;
    private Set<String> properties;
    private Set<String> aspects;
    private Set<String> mimeTypes;
    private Set<String> sites;

    public List<NodeEntity> getNodesByAclChangesetId(Pair<Long, StoreRef> store, Long lastAclChangesetId, int maxResults)
    {
        StoreRef storeRef = store.getSecond();
        if (maxResults <= 0 || maxResults == Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Maximum results must be a reasonable number.");
        }

        logger.debug("[getNodesByAclChangesetId] On Store " + storeRef.getProtocol() + "://" + storeRef.getIdentifier());

        NodeBatchLoadEntity nodeLoadEntity = new NodeBatchLoadEntity();
        nodeLoadEntity.setStoreId(store.getFirst());
        nodeLoadEntity.setStoreProtocol(storeRef.getProtocol());
        nodeLoadEntity.setStoreIdentifier(storeRef.getIdentifier());
        nodeLoadEntity.setMinId(lastAclChangesetId);
        nodeLoadEntity.setMaxId(lastAclChangesetId + maxResults);
        nodeLoadEntity.setAllowedTypes(this.allowedTypes);
        nodeLoadEntity.setExcludedNameExtension(this.excludedNameExtension);
//        nodeLoadEntity.setProperties(this.properties);
        nodeLoadEntity.setAspects(this.aspects);
        nodeLoadEntity.setMimeTypes(this.mimeTypes);

        return filterNodes((List<NodeEntity>) template.selectList(SELECT_NODES_BY_ACLS, nodeLoadEntity, new RowBounds(0,
                Integer.MAX_VALUE)));
    }

    public List<NodeEntity> getNodesByTransactionId(Pair<Long, StoreRef> store, Long lastTransactionId, int maxResults)
    {
        StoreRef storeRef = store.getSecond();
        if (maxResults <= 0 || maxResults == Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Maximum results must be a reasonable number.");
        }

        logger.debug("[getNodesByTransactionId] On Store " + storeRef.getProtocol() + "://" + storeRef.getIdentifier());

        NodeBatchLoadEntity nodeLoadEntity = new NodeBatchLoadEntity();
        nodeLoadEntity.setStoreId(store.getFirst());
        nodeLoadEntity.setStoreProtocol(storeRef.getProtocol());
        nodeLoadEntity.setStoreIdentifier(storeRef.getIdentifier());
        nodeLoadEntity.setMinId(lastTransactionId);
        nodeLoadEntity.setMaxId(lastTransactionId + maxResults);
        nodeLoadEntity.setAllowedTypes(this.allowedTypes);
        nodeLoadEntity.setExcludedNameExtension(this.excludedNameExtension);
//        nodeLoadEntity.setProperties(this.properties);
        nodeLoadEntity.setAspects(this.aspects);
        nodeLoadEntity.setMimeTypes(this.mimeTypes);

        return filterNodes((List<NodeEntity>) template.selectList(SELECT_NODES_BY_TXNS, nodeLoadEntity, new RowBounds(0,
                Integer.MAX_VALUE)));
    }

    public NodeEntity getNodeByUuid(Pair<Long, StoreRef> store, String uuid)
    {
        StoreRef storeRef = store.getSecond();

        logger.debug("[getNodeByUuid] On Store " + storeRef.getProtocol() + "://" + storeRef.getIdentifier());

        NodeBatchLoadEntity nodeLoadEntity = new NodeBatchLoadEntity();
        nodeLoadEntity.setStoreId(store.getFirst());
        nodeLoadEntity.setStoreProtocol(storeRef.getProtocol());
        nodeLoadEntity.setStoreIdentifier(storeRef.getIdentifier());
        nodeLoadEntity.setUuid(uuid);

        return (NodeEntity) template.selectOne(SELECT_NODES_BY_UUID, nodeLoadEntity);
    }
    
    /**
     * Filter the nodes based on some parameters
     * @param nodes
     * @return
     */
    private List<NodeEntity> filterNodes(List<NodeEntity> nodes)
    {
        List<NodeEntity> filteredNodes= null;
        
        //Filter by sites
        Map<String,Boolean> filters=getFilters();
        
        if(filters.values().contains(Boolean.TRUE)){
            
            filteredNodes= new ArrayList<NodeEntity>();
            
            for(NodeEntity node:nodes){
                
               boolean shouldBeAdded=true;
               NodeRef nodeRef= new NodeRef(node.getStore().getStoreRef(),node.getUuid());
                    
               if(nodeService.exists(nodeRef)){
                    
                    //Filter by site
                    if(filters.get("SITE")){
                        String siteName=siteService.getSiteShortName(nodeRef);
                        shouldBeAdded= siteName!=null && this.sites.contains(siteName);
                    }
                    
                    //Filter by properties
                    if(filters.get("PROPERTIES") && shouldBeAdded){
                        for(String prop:this.properties){
                            
                            int pos=prop.lastIndexOf(":");
                            String qName=null;
                            String value=null;
                            
                            if(pos!=-1 && (prop.length()-1)>pos){
                                qName=prop.substring(0, pos);
                                value= prop.substring(pos+1,prop.length());
                            }
                            
                            if(StringUtils.isEmpty(qName) || StringUtils.isEmpty(value)){
                                //Invalid property
                                continue;
                            }
                            
                            Serializable rawValue= nodeService.getProperty(nodeRef, QName.createQName(qName));
                            shouldBeAdded=shouldBeAdded && value.equals(rawValue);
                            
                        }
                    }
                    
                    if(shouldBeAdded){
                        filteredNodes.add(node);
                    }
                }
            }
        }else{
            filteredNodes=nodes;
        }
        return filteredNodes;
    }

    /**
     * Get existing filters
     * @return
     */
    private Map<String,Boolean> getFilters()
    {
        Map<String,Boolean> filters= new HashMap<String, Boolean>(2);
        //Site filter
        filters.put("SITE", this.sites!=null && this.sites.size() > 0);
        //Properties filter
        filters.put("PROPERTIES", this.properties!=null && this.properties.size() > 0);
        
        return filters;
    }

    public void setSqlSessionTemplate(SqlSessionTemplate sqlSessionTemplate)
    {
        this.template = sqlSessionTemplate;
    }
    
    public void setServiceRegistry(ServiceRegistry serviceRegistry){
        this.siteService=serviceRegistry.getSiteService();
        this.nodeService= serviceRegistry.getNodeService();
    }

    public void setAllowedTypes(Set<String> allowedTypes)
    {
        this.allowedTypes = allowedTypes;
    }

    public Set<String> getAllowedTypes()
    {
        return this.allowedTypes;
    }

    public void setExcludedNameExtension(Set<String> excludedNameExtension)
    {
        this.excludedNameExtension = excludedNameExtension;
    }

    public Set<String> getExcludedNameExtension()
    {
        return this.excludedNameExtension;
    }

    public void setProperties(Set<String> properties)
    {
        this.properties = properties;
    }

    public Set<String> getProperties()
    {
        return this.properties;
    }

    public void setAspects(Set<String> aspects)
    {
        this.aspects = aspects;
    }

    public Set<String> getAspects()
    {

        return this.aspects;
    }

    public void setMimeTypes(Set<String> mimeTypes)
    {
        this.mimeTypes = mimeTypes;
    }

    public Set<String> getMimeTypes()
    {
        return this.mimeTypes;
    }
    
    public void setSites(Set<String> sites)
    {
        this.sites = sites;
    }

    public Set<String> getSites()
    {
        return this.sites;
    }

}
