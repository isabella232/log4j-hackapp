package com.sonatype.demo.log4shell;

import lombok.Data;
import lombok.extern.java.Log;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

@Data
public class DriverConfig {

    JavaVersion jv;
    LogVersion lv;
    List<SystemProperty> vmargs;
    Set<String> reportingProperties=new HashSet<>();
    String[] msgs;


    public DriverConfig(JavaVersion jv, LogVersion lv,List<SystemProperty> vmprops,String msgs[]) {
        this.jv=jv;
        this.lv=lv;
        this.msgs=msgs;
        this.vmargs=vmprops;
        reportingProperties.add("java.version");
    }

    public Integer[] getActivePropertyIDs() {
        List<Integer> li=new LinkedList<>();

        for(SystemProperty s:vmargs) {
            li.add(s.id);
        }
        return li.toArray(new Integer[0]);
    }
}
