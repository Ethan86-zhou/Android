package com.agm.blank;

/** Device-to-world attitude; after seeding, only three-axis angular velocity updates it. */
public final class GyroHeading {
    private final double[] r = new double[9];
    private long timestamp;
    public boolean initialized;
    public void reset() { initialized=false; timestamp=0; }
    public void zero(long time) { align(new float[]{1,0,0,0,1,0,0,0,1},time); }
    public void align(float[] matrix, long time) {
        for(int i=0;i<9;i++) r[i]=matrix[i];
        timestamp=time; initialized=true;
    }
    public void update(float x,float y,float z,long time) {
        if(!initialized)return;
        double dt=(time-timestamp)*1e-9; timestamp=time;
        if(dt<=0)return;
        if(dt>.25) { reset(); return; }
        double speed=Math.sqrt(x*x+y*y+z*z), angle=speed*dt;
        if(speed<1e-9)return;
        double a=x/speed,b=y/speed,c=z/speed,s=Math.sin(angle),co=Math.cos(angle),v=1-co;
        double[] delta={co+a*a*v,a*b*v-c*s,a*c*v+b*s,b*a*v+c*s,co+b*b*v,b*c*v-a*s,c*a*v-b*s,c*b*v+a*s,co+c*c*v};
        double[] out=new double[9];
        for(int row=0;row<3;row++)for(int col=0;col<3;col++)for(int k=0;k<3;k++)out[row*3+col]+=r[row*3+k]*delta[k*3+col];
        System.arraycopy(out,0,r,0,9);
    }
    public float[] matrix() { float[] out=new float[9]; for(int i=0;i<9;i++)out[i]=(float)r[i]; return out; }
}
