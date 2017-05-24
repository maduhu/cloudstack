// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.storage.resource;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.cloud.agent.api.to.DatadiskTO;
import com.cloud.utils.exception.CloudRuntimeException;

public class OVFHelper {
    private static final Logger s_logger = Logger.getLogger(OVFHelper.class);

    public List<DatadiskTO> getOVFVolumeInfo(final String ovfFilePath) {
        if (ovfFilePath == null || ovfFilePath.isEmpty()) {
            return null;
        }
        ArrayList<OVFFile> vf = new ArrayList<OVFFile>();
        ArrayList<OVFDisk> vd = new ArrayList<OVFDisk>();

        try {
            final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(ovfFilePath));
            NodeList disks = doc.getElementsByTagName("Disk");
            NodeList files = doc.getElementsByTagName("File");
            NodeList items = doc.getElementsByTagName("Item");
            boolean toggle = true;
            for (int j = 0; j < files.getLength(); j++) {
                Element file = (Element)files.item(j);
                OVFFile of = new OVFFile();
                of._href = file.getAttribute("ovf:href");
                if (of._href.endsWith("vmdk") || of._href.endsWith("iso")) {
                    s_logger.info("MDOVA getOVFVolumeInfo File href = " + of._href);
                    of._id = file.getAttribute("ovf:id");
                    s_logger.info("MDOVA getOVFVolumeInfo File Id = " + of._id);
                    String size = file.getAttribute("ovf:size");
                    if (size != null && !size.isEmpty()) {
                        of._size = Long.parseLong(size);
                    }
                    if (toggle) {
                        of._bootable = true;
                        toggle = !toggle;
                    }
                    of._iso = of._href.endsWith("iso");
                    vf.add(of);
                }
            }
            for (int i = 0; i < disks.getLength(); i++) {
                Element disk = (Element)disks.item(i);
                OVFDisk od = new OVFDisk();
                String virtualSize = disk.getAttribute("ovf:capacity");
                if (virtualSize == null || virtualSize.isEmpty()) {
                    od._capacity = 0L;
                } else {
                    od._capacity = Long.parseLong(virtualSize);
                }
                String allocationUnits = disk.getAttribute("ovf:capacityAllocationUnits");
                od._diskId = disk.getAttribute("ovf:diskId");
                s_logger.info("MDOVA getOVFVolumeInfo Disk ovf:diskId  = " + od._diskId);
                od._fileRef = disk.getAttribute("ovf:fileRef");
                s_logger.info("MDOVA getOVFVolumeInfo Disk ovf:fileRef  = " + od._fileRef);
                od._populatedSize = Long.parseLong(disk.getAttribute("ovf:populatedSize"));
                s_logger.info("MDOVA getOVFVolumeInfo Disk _populatedSize  = " + od._populatedSize);

                if ((od._capacity != 0) && (allocationUnits != null)) {

                    long units = 1;
                    if (allocationUnits.equalsIgnoreCase("KB") || allocationUnits.equalsIgnoreCase("KiloBytes") || allocationUnits.equalsIgnoreCase("byte * 2^10")) {
                        units = 1024;
                    } else if (allocationUnits.equalsIgnoreCase("MB") || allocationUnits.equalsIgnoreCase("MegaBytes") || allocationUnits.equalsIgnoreCase("byte * 2^20")) {
                        units = 1024 * 1024;
                    } else if (allocationUnits.equalsIgnoreCase("GB") || allocationUnits.equalsIgnoreCase("GigaBytes") || allocationUnits.equalsIgnoreCase("byte * 2^30")) {
                        units = 1024 * 1024 * 1024;
                    }
                    od._capacity = od._capacity * units;
                    s_logger.info("MDOVA getOVFVolumeInfo Disk _capacity  = " + od._capacity);
                }
                od._controller = getControllerType(items, od._diskId);
                vd.add(od);
            }

        } catch (SAXException | IOException | ParserConfigurationException e) {
            s_logger.error("Unexpected exception caught while parsing ovf file:" + ovfFilePath, e);
            throw new CloudRuntimeException(e);
        }

        List<DatadiskTO> disksTO = new ArrayList<DatadiskTO>();
        File ovfFile = new File(ovfFilePath);
        for (OVFFile of : vf) {
            OVFDisk cdisk = getDisk(of._id, vd);
            Long capacity = cdisk == null ? of._size : cdisk._capacity;
            String controller =  cdisk == null ? "" : cdisk._controller._name;
            String controllerSubType =  cdisk == null ? "" : cdisk._controller._subType;
            String dataDiskPath = ovfFile.getParent() + File.separator + of._href;
            s_logger.info("MDOVA getOVFVolumeInfo diskName = " + of._href + ", dataDiskPath = " + dataDiskPath);
            disksTO.add(new DatadiskTO(dataDiskPath, capacity, of._size, of._id, of._iso, of._bootable, controller, controllerSubType));
        }
        return disksTO;
    }

    private OVFDiskController getControllerType(final NodeList itemList, final String diskId) {
        for (int k = 0; k < itemList.getLength(); k++) {
            Element item = (Element)itemList.item(k);
            NodeList cn = item.getChildNodes();
            for (int l = 0; l < cn.getLength(); l++) {
                if (cn.item(l) instanceof Element) {
                    Element el = (Element)cn.item(l);
                    if ("rasd:HostResource".equals(el.getNodeName())
                            && (el.getTextContent().contains("ovf:/file/" + diskId) || el.getTextContent().contains("ovf:/disk/" + diskId))) {
                        Element oe = getParentNode(itemList, item);
                        Element voe = oe;
                        while (oe != null) {
                            voe = oe;
                            oe = getParentNode(itemList, voe);
                        }
                        return getController(voe);
                    }
                }
            }
        }
        return null;
    }

    private Element getParentNode(final NodeList itemList, final Element childItem) {
        NodeList cn = childItem.getChildNodes();
        String parent_id = null;
        for (int l = 0; l < cn.getLength(); l++) {
            if (cn.item(l) instanceof Element) {
                Element el = (Element)cn.item(l);
                if ("rasd:Parent".equals(el.getNodeName())) {
                    s_logger.info("MDOVA parent id " + el.getTextContent());
                    parent_id = el.getTextContent();
                }
            }
        }
        if (parent_id != null) {
            for (int k = 0; k < itemList.getLength(); k++) {
                Element item = (Element)itemList.item(k);
                NodeList child = item.getChildNodes();
                for (int l = 0; l < child.getLength(); l++) {
                    if (child.item(l) instanceof Element) {
                        Element el = (Element)child.item(l);
                        if ("rasd:InstanceID".equals(el.getNodeName()) && el.getTextContent().trim().equals(parent_id)) {
                            s_logger.info("MDOVA matching parent entry " + el.getTextContent());
                            return item;
                        }
                    }
                }
            }
        }
        return null;
    }

    private OVFDiskController getController(Element controllerItem) {
        OVFDiskController dc = new OVFDiskController();
        NodeList child = controllerItem.getChildNodes();
        for (int l = 0; l < child.getLength(); l++) {
            if (child.item(l) instanceof Element) {
                Element el = (Element)child.item(l);
                if ("rasd:ElementName".equals(el.getNodeName())) {
                    s_logger.info("MDOVA controller name " + el.getTextContent());
                    dc._name = el.getTextContent();
                }
                if ("rasd:ResourceSubType".equals(el.getNodeName())) {
                    s_logger.info("MDOVA controller sub type " + el.getTextContent());
                    dc._subType = el.getTextContent();
                }
            }
        }
        return dc;
    }

    public void rewriteOVFFile(final String origOvfFilePath, final String newOvfFilePath, final String diskName) {
        try {
            final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(origOvfFilePath));
            NodeList disks = doc.getElementsByTagName("Disk");
            NodeList files = doc.getElementsByTagName("File");
            NodeList items = doc.getElementsByTagName("Item");
            String keepfile = null;
            List<Element> toremove = new ArrayList<Element>();
            for (int j = 0; j < files.getLength(); j++) {
                Element file = (Element)files.item(j);
                String href = file.getAttribute("ovf:href");
                s_logger.info("MDOVA rewriteOVFFile href= " + href);
                if (diskName.equals(href)) {
                    keepfile = file.getAttribute("ovf:id");
                    s_logger.info("MDOVA rewriteOVFFile keeping file = " + file.getAttribute("ovf:id"));
                } else {
                    s_logger.info("MDOVA rewriteOVFFile removing file = " + file.getAttribute("ovf:id"));
                    toremove.add(file);
                }
            }
            String keepdisk = null;
            for (int i = 0; i < disks.getLength(); i++) {
                Element disk = (Element)disks.item(i);
                String fileRef = disk.getAttribute("ovf:fileRef");
                if (keepfile == null) {
                    s_logger.info("FATAL: OVA format error");
                } else if (keepfile.equals(fileRef)) {
                    s_logger.info("MDOVA rewriteOVFFile keeping disk = " + fileRef);
                    keepdisk = disk.getAttribute("ovf:diskId");
                } else {
                    s_logger.info("MDOVA rewriteOVFFile removing disk = " + fileRef);
                    s_logger.info("MDOVA rewriteOVFFile id = " + disk.getAttribute("ovf:diskId"));
                    toremove.add(disk);
                }
            }
            for (int k = 0; k < items.getLength(); k++) {
                Element item = (Element)items.item(k);
                NodeList cn = item.getChildNodes();
                for (int l = 0; l < cn.getLength(); l++) {
                    if (cn.item(l) instanceof Element) {
                        Element el = (Element)cn.item(l);
                        if ("rasd:HostResource".equals(el.getNodeName())
                                && !(el.getTextContent().contains("ovf:/file/" + keepdisk) || el.getTextContent().contains("ovf:/disk/" + keepdisk))) {
                            s_logger.info("MDOVA to remove " + el.getTextContent());
                            toremove.add(item);
                            break;
                        }
                    }
                }
            }

            for (Element rme : toremove) {
                s_logger.info("MDOVA remove " + rme.getTagName());
                if (rme.getParentNode() != null) {
                    rme.getParentNode().removeChild(rme);
                }
            }

            final StringWriter writer = new StringWriter();
            final StreamResult result = new StreamResult(writer);
            final TransformerFactory tf = TransformerFactory.newInstance();
            final Transformer transformer = tf.newTransformer();
            final DOMSource domSource = new DOMSource(doc);
            transformer.transform(domSource, result);
            PrintWriter outfile = new PrintWriter(newOvfFilePath);
            outfile.write(writer.toString());
            outfile.close();
        } catch (SAXException | IOException | ParserConfigurationException | TransformerException e) {
            s_logger.info("Unexpected exception caught while removing network elements from OVF:" + e.getMessage(), e);
            throw new CloudRuntimeException(e);
        }
    }

    OVFDisk getDisk(String fileRef, List<OVFDisk> disks) {
        for (OVFDisk disk : disks) {
            if (disk._fileRef.equals(fileRef)) {
                return disk;
            }
        }
        return null;
    }

    class OVFFile {
        // <File ovf:href="i-2-8-VM-disk2.vmdk" ovf:id="file1" ovf:size="69120" />
        public String _href;
        public String _id;
        public Long _size;
        public boolean _bootable;
        public boolean _iso;
    }

    class OVFDisk {
        //<Disk ovf:capacity="50" ovf:capacityAllocationUnits="byte * 2^20" ovf:diskId="vmdisk2" ovf:fileRef="file2"
        //ovf:format="http://www.vmware.com/interfaces/specifications/vmdk.html#streamOptimized" ovf:populatedSize="43319296" />
        public Long _capacity;
        public String _capacityUnit;
        public String _diskId;
        public String _fileRef;
        public Long _populatedSize;
        public OVFDiskController _controller;
    }

    class OVFDiskController {
        public String _name;
        public String _subType;
    }
}
