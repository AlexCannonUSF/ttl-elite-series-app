package com.ttl.tabletennis.Service;

import com.ttl.tabletennis.Entity.Match;
import com.ttl.tabletennis.Entity.Player;
import com.ttl.tabletennis.Repository.MatchRepository;
import com.ttl.tabletennis.model.AdvancedPlayerStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AdvancedStatisticsService {
    @Autowired private MatchRepository matches;

    public AdvancedPlayerStats recent(Player p,int n){
        List<Match> all=matches.findAll(),mine=new ArrayList<>();
        for(Match m:all) if(p.equals(m.getPlayer1())||p.equals(m.getPlayer2())) mine.add(m);
        mine.sort(Comparator.comparing(Match::getDate,Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        int W=0,L=0,ct=0,streak=0; Boolean sWin=null; int setDiffSum=0;
        for(Match m:mine){
            if(ct==n)break;
            String r=m.getResult(); if(r==null||r.isBlank())continue;
            String[] parts=r.split("[:/-]"); if(parts.length!=2)continue;
            try{
                int a=Integer.parseInt(parts[0].trim()),b=Integer.parseInt(parts[1].trim());
                boolean isP1=p.equals(m.getPlayer1()); int won=isP1?a:b, lost=isP1?b:a;
                if(won>lost){W++; if(sWin==null||sWin) streak++; else break; sWin=true;}
                else {L++; if(sWin==null||!sWin) streak++; else break; sWin=false;}
                setDiffSum+=won-lost; ct++;
            }catch(Exception ignored){}
        }
        double avg=ct>0?(double)setDiffSum/ct:0.0;
        return new AdvancedPlayerStats(ct,W,L,avg,streak,sWin!=null&&sWin);
    }

    public AdvancedPlayerStats last5(Player p){return recent(p,5);}
    public AdvancedPlayerStats last10(Player p){return recent(p,10);}
    public AdvancedPlayerStats last50(Player p){return recent(p,50);}
}