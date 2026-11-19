import sys, re, numpy as np
from fk import Model
from collide import Body, descends
prefix=sys.argv[1]; clip=sys.argv[2] if len(sys.argv)>2 else 'idle'
m=Model(prefix); b=Body(prefix,m)
rot,pos,sc=m.pose([clip],0.0,False)
p,_=b.skin(rot,pos,sc)
st=b.joints[np.arange(len(b.joints)),b.weights.argmax(1)]
bad=np.array([descends(m,n,'RightArm') or descends(m,n,'LeftArm') or descends(m,n,'Head') or bool(re.search('(?i)hair',n)) for n in b.names])
k=~bad[st]
for y in range(30,6,-2):
    s=k&(abs(p[:,1]-y)<1)
    if s.sum()==0: continue
    q=p[s]
    # right side (+x): max x; front: min z ; back max z
    print(f"y={y:3d} x:[{q[:,0].min():6.2f},{q[:,0].max():6.2f}] z:[{q[:,2].min():6.2f},{q[:,2].max():6.2f}]  (|z|<2 band x max {q[abs(q[:,2])<2][:,0].max() if (abs(q[:,2])<2).any() else 0:5.2f})")
for n in ['RightArm','RightForeArm','RightHand','RightHandLocator','RightLeg']:
    print(n, np.round(m.point(n,rot,pos,sc),2))
