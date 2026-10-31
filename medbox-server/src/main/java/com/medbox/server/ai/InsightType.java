package com.medbox.server.ai;

public enum InsightType{
    ADHERENCE,STOCK,ENV_CORRELATION,TREND,COMPREHENSIVE;

    /** 空 → COMPREHENSIVE；未知 → null（调用方报 40001）。 */
    public static InsightType parse(String raw){
        if(raw==null||raw.isBlank()){
            return COMPREHENSIVE;
        }
        for(InsightType t:values()){
            if(t.name().equalsIgnoreCase(raw.trim())){
                return t;
            }
        }
        return null;
    }

}