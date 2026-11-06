// One VNDF formula; the includer chooses the arithmetic, not a second sampling algorithm.
float3 Ve = float3(D_DOT(ve,t),D_DOT(ve,b),D_DOT(ve,n));
float3 Vh = D_NORMALIZE(float3(D_MUL(ax,Ve.x),D_MUL(ay,Ve.y),Ve.z));
float lensq = D_ADD(D_MUL(Vh.x,Vh.x),D_MUL(Vh.y,Vh.y));
float3 T1 = lensq > 0.0 ? D_MUL(float3(-Vh.y,Vh.x,0.0),rsqrt(lensq)) : float3(1.0,0.0,0.0);
float3 T2 = D_CROSS(Vh,T1);
float u1 = rndf(s);
float u2 = rndf(s);
float r = sqrt(u1);
float phi = D_MUL(6.2831853,u2);
float p1 = D_MUL(r,cos(phi));
float p2 = D_MUL(r,sin(phi));
float ss = D_MUL(0.5,D_ADD(1.0,Vh.z));
p2 = D_ADD(D_MUL(D_SUB(1.0,ss),sqrt(D_SUB(1.0,D_MUL(p1,p1)))),D_MUL(ss,p2));
float3 Nh = D_ADD(D_ADD(D_MUL(p1,T1),D_MUL(p2,T2)),
        D_MUL(sqrt(max(0.0,D_SUB(D_SUB(1.0,D_MUL(p1,p1)),D_MUL(p2,p2)))),Vh));
float3 Ne = D_NORMALIZE(float3(D_MUL(ax,Nh.x),D_MUL(ay,Nh.y),max(0.0,Nh.z)));
return D_NORMALIZE(D_ADD(D_ADD(D_MUL(Ne.x,t),D_MUL(Ne.y,b)),D_MUL(Ne.z,n)));
