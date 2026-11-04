package dev.phonelinux.handshakefixture;
import java.util.Map;
public final class OwnedAnchorTest {
 static void reject(Map<Integer,OwnedTree.Id>b,Map<Integer,OwnedTree.Id>a){try{OwnedTree.newDirectChild(b,a,10);throw new AssertionError("accepted");}catch(IllegalStateException e){/* expected */}}
 public static void main(String[]args){
  OwnedTree.Id old=new OwnedTree.Id(20,10,100),one=new OwnedTree.Id(30,10,101),other=new OwnedTree.Id(40,10,102),grand=new OwnedTree.Id(50,30,103);
  if(OwnedTree.newDirectChild(Map.of(20,old),Map.of(20,old,30,one,50,grand),10)!=one)throw new AssertionError();
  reject(Map.of(),Map.of());reject(Map.of(),Map.of(30,one,40,other));reject(Map.of(),Map.of(50,grand));reject(Map.of(30,one),Map.of(30,new OwnedTree.Id(30,10,999)));
  System.out.println("ACTUAL_OWNED_ANCHOR_SELECTION_5_CHECKS_PASS_NO_PROCESS_RUN");
 }
}
