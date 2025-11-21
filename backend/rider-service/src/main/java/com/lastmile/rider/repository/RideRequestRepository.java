package com.lastmile.rider.repository;

import com.lastmile.rider.model.RideRequest;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public interface RideRequestRepository extends MongoRepository<RideRequest, String> {
	List<RideRequest> findByRiderId(String riderId);
}

