package com.joshblour.discovery;

/**
 * Created by Yonah on 16/10/15.
 */
public class EasedValue {
    private Float mValue;
    private Float mVelocity;
    private Float mTargetValue;
    private Float mCurrentValue;

    public EasedValue() {
        this.mVelocity = 0.0f;
        this.mTargetValue = 0.0f;
        this.mCurrentValue = 0.0f;
    }

    public void setValue(Float value) {
        this.mTargetValue = value;
    }

    public Float getValue() {
        return mCurrentValue;
    }

    public void update() {
        // determine speed at which the ease will happen
        // this is based on difference between target and current value
        mVelocity += (mTargetValue - mCurrentValue) * 0.01f;
        mVelocity *= 0.7f;

        // ease the current value
        mCurrentValue += mVelocity;

        // limit how small the ease can get
        if(Math.abs(mTargetValue - mCurrentValue) < 0.001f){
            mCurrentValue = mTargetValue;
            mVelocity = 0.0f;
        }

        // keep above zero
        mCurrentValue = Math.max(0.0f, mCurrentValue);

    }

    public void reset() {
        mCurrentValue = mTargetValue;
    }

}
