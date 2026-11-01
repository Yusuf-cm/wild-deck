package com.wilddeck.world;

import java.util.Objects;

/**
 * An empty spherical world. Kilometres are the spatial unit and angles are
 * degrees. Surface positions are continuous; no terrain or region grid exists.
 */
public record Planet(String name,double radiusKm) {
    public static final double EARTH_MEAN_RADIUS_KM=6371.0;

    public Planet {
        if(name==null||name.isBlank())throw new IllegalArgumentException("Planet needs a name");
        if(!Double.isFinite(radiusKm)||radiusKm<=0)
            throw new IllegalArgumentException("Radius must be a finite positive distance");
    }
    public static Planet emptyEarthSized(){
        return new Planet("Wild Deck",EARTH_MEAN_RADIUS_KM);
    }
    public double diameterKm(){return radiusKm*2;}
    public double circumferenceKm(){return 2*Math.PI*radiusKm;}
    public double surfaceAreaKm2(){return 4*Math.PI*radiusKm*radiusKm;}

    /**
     * Latitude: -90 south to +90 north. Longitude: -180 to +180, with +180
     * canonicalized to -180 because they describe the same meridian.
     */
    public record SurfacePoint(double latitudeDeg,double longitudeDeg) {
        public SurfacePoint {
            if(!Double.isFinite(latitudeDeg)||latitudeDeg < -90||latitudeDeg > 90||
               !Double.isFinite(longitudeDeg)||longitudeDeg < -180||longitudeDeg > 180)
                throw new IllegalArgumentException("Invalid latitude or longitude");
            if(longitudeDeg==180)longitudeDeg=-180;
        }
    }
    public record CartesianPoint(double xKm,double yKm,double zKm) {}

    /** Shortest great-circle distance, with no artificial edge at the date line. */
    public double surfaceDistanceKm(SurfacePoint from,SurfacePoint to){
        Objects.requireNonNull(from);Objects.requireNonNull(to);
        double lat1=Math.toRadians(from.latitudeDeg()),lat2=Math.toRadians(to.latitudeDeg());
        double dLat=lat2-lat1,dLon=Math.toRadians(to.longitudeDeg()-from.longitudeDeg());
        double a=Math.pow(Math.sin(dLat/2),2)+Math.cos(lat1)*Math.cos(lat2)*Math.pow(Math.sin(dLon/2),2);
        a=Math.max(0,Math.min(1,a));
        return radiusKm*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
    }
    /**
     * Geocentric position: origin at the centre; +X at latitude/longitude 0/0,
     * +Y at 0/90 east, +Z at the north pole. This is geometry, not rendered art.
     */
    public CartesianPoint toCartesianKm(SurfacePoint point){
        Objects.requireNonNull(point);
        double latitude=Math.toRadians(point.latitudeDeg()),longitude=Math.toRadians(point.longitudeDeg());
        return new CartesianPoint(radiusKm*Math.cos(latitude)*Math.cos(longitude),
            radiusKm*Math.cos(latitude)*Math.sin(longitude),radiusKm*Math.sin(latitude));
    }
}
