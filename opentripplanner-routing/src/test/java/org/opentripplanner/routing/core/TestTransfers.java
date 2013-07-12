/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.routing.core;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import junit.framework.TestCase;

import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Stop;
import org.onebusaway.gtfs.model.Trip;
import org.onebusaway.gtfs.model.calendar.CalendarServiceData;
import org.opentripplanner.ConstantsForTests;
import org.opentripplanner.gtfs.GtfsContext;
import org.opentripplanner.gtfs.GtfsLibrary;
import org.opentripplanner.routing.algorithm.GenericAStar;
import org.opentripplanner.routing.edgetype.SimpleTransfer;
import org.opentripplanner.routing.edgetype.TableTripPattern;
import org.opentripplanner.routing.edgetype.TimedTransferEdge;
import org.opentripplanner.routing.edgetype.factory.GTFSPatternHopFactory;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.routing.spt.ShortestPathTree;
import org.opentripplanner.routing.trippattern.Update;
import org.opentripplanner.routing.trippattern.UpdateBlock;
import org.opentripplanner.routing.trippattern.Update.Status;
import org.opentripplanner.routing.vertextype.PatternStopVertex;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.opentripplanner.util.TestUtils;

/**
 * This is a singleton class to hold graph data between test runs, since loading it is slow.
 */
class Context {
    
    public Graph graph;

    public GenericAStar aStar = new GenericAStar();

    private static Context instance = null;

    public static Context getInstance() throws IOException {
        if (instance == null) {
            instance = new Context();
        }
        return instance;
    }

    public Context() throws IOException {
        // Create graph
        GtfsContext context = GtfsLibrary.readGtfs(new File(ConstantsForTests.FAKE_GTFS));
        graph = spy(new Graph());
        GTFSPatternHopFactory factory = new GTFSPatternHopFactory(context);
        factory.run(graph);
        graph.putService(CalendarServiceData.class,
                GtfsLibrary.createCalendarServiceData(context.getDao()));
        
        // Add simple transfer to make transfer possible between N-K and F-H
        @SuppressWarnings("deprecation")
        TransitStop from = ((TransitStop) graph.getVertex("agency_K"));
        @SuppressWarnings("deprecation")
        TransitStop to = ((TransitStop) graph.getVertex("agency_F"));
        new SimpleTransfer(from, to, 100);
        
    }
}

/**
 * Test transfers, mostly stop-to-stop transfers.
 */
public class TestTransfers extends TestCase {
    
    private Graph graph;

    private GenericAStar aStar;

    public void setUp() throws Exception {
        // Get graph and a star from singleton class
        graph = Context.getInstance().graph;
        aStar = Context.getInstance().aStar;
    }

    /**
     * Plan journey without optimization and return list with trips
     * @param options are options to use for planning the journey
     * @return ordered list of trips in the journey
     */
    private List<Trip> planJourney(RoutingRequest options) {
        // Calculate route and convert to path
        ShortestPathTree spt = aStar.getShortestPathTree(options);
        GraphPath path = spt.getPath(options.rctx.target, false);
        
        // Get all trips in order
        List<Trip> trips = new ArrayList<Trip>();
        if (path != null) {
            for (State s : path.states) {
                if (s.getBackMode() != null && s.getBackMode().isTransit()) {
                    Trip trip = s.getBackTrip();
                    if (trip != null && !trips.contains(trip)) {
                        trips.add(trip);
                    }
                }
            }
        }
        
        // Return trips
        return trips;
    }

    /**
     * Apply an update to a table trip pattern and check whether the update was applied correctly
     */
    private void applyUpdateToTripPattern(TableTripPattern pattern, String tripId, String stopId,
            int stopSeq, int arrive, int depart, Status prediction, int timestamp) {
        Update update = new Update(new AgencyAndId("agency",tripId), stopId, stopSeq, arrive, depart, prediction, timestamp);
        ArrayList<Update> updates = new ArrayList<Update>(Arrays.asList(update));
        UpdateBlock block = UpdateBlock.splitByTrip(updates).get(0);
        boolean success = pattern.update(block);
        assertTrue(success);
    }
    
    public void testStopToStopTransfer() throws Exception {
        // Replace the transfer table with an empty table
        TransferTable table = new TransferTable();
        when(graph.getTransferTable()).thenReturn(table);
        
        // Compute a normal path between two stops
        @SuppressWarnings("deprecation")
        Vertex origin = graph.getVertex("agency_N");
        @SuppressWarnings("deprecation")
        Vertex destination = graph.getVertex("agency_H");

        // Set options like time and routing context
        RoutingRequest options = new RoutingRequest();
        options.dateTime = TestUtils.dateInSeconds("America/New_York", 2009, 7, 11, 11, 11, 0);
        options.setRoutingContext(graph, origin, destination);

        // Plan journey
        List<Trip> trips;
        trips = planJourney(options);
        // Validate result
        assertEquals("8.1", trips.get(0).getId().getId());
        assertEquals("4.2", trips.get(1).getId().getId());
        
        // Add transfer to table, transfer time was 27600 seconds
        Stop stopK = new Stop();
        stopK.setId(new AgencyAndId("agency", "K"));
        Stop stopF = new Stop();
        stopF.setId(new AgencyAndId("agency", "F"));
        table.addTransferTime(stopK, stopF, null, null, null, null, 27601);
        
        // Plan journey
        trips = planJourney(options);
        // Check whether a later second trip was taken
        assertEquals("8.1", trips.get(0).getId().getId());
        assertEquals("4.3", trips.get(1).getId().getId());
        
        // Revert the graph, thus using the original transfer table again
        reset(graph);
    }

    public void testForbiddenStopToStopTransfer() throws Exception {
        // Replace the transfer table with an empty table
        TransferTable table = new TransferTable();
        when(graph.getTransferTable()).thenReturn(table);
        
        // Compute a normal path between two stops
        @SuppressWarnings("deprecation")
        Vertex origin = graph.getVertex("agency_N");
        @SuppressWarnings("deprecation")
        Vertex destination = graph.getVertex("agency_H");
        
        // Set options like time and routing context
        RoutingRequest options = new RoutingRequest();
        options.dateTime = TestUtils.dateInSeconds("America/New_York", 2009, 7, 11, 11, 11, 0);
        options.setRoutingContext(graph, origin, destination);
        
        // Plan journey
        List<Trip> trips;
        trips = planJourney(options);
        // Validate result
        assertEquals("8.1", trips.get(0).getId().getId());
        assertEquals("4.2", trips.get(1).getId().getId());
        
        // Add forbidden transfer to table
        Stop stopK = new Stop();
        stopK.setId(new AgencyAndId("agency", "K"));
        Stop stopF = new Stop();
        stopF.setId(new AgencyAndId("agency", "F"));
        table.addTransferTime(stopK, stopF, null, null, null, null, StopTransfer.FORBIDDEN_TRANSFER);
        
        // Plan journey
        trips = planJourney(options);
        // Check that no trip was returned
        assertEquals(0, trips.size());
        
        // Revert the graph, thus using the original transfer table again
        reset(graph);
    }
    
    public void testTimedStopToStopTransfer() throws Exception {
        // Replace the transfer table with an empty table
        TransferTable table = new TransferTable();
        when(graph.getTransferTable()).thenReturn(table);
        
        // Compute a normal path between two stops
        @SuppressWarnings("deprecation")
        Vertex origin = graph.getVertex("agency_N");
        @SuppressWarnings("deprecation")
        Vertex destination = graph.getVertex("agency_H");
        
        // Set options like time and routing context
        RoutingRequest options = new RoutingRequest();
        options.dateTime = TestUtils.dateInSeconds("America/New_York", 2009, 7, 11, 11, 11, 0);
        options.setRoutingContext(graph, origin, destination);
        
        // Plan journey
        List<Trip> trips;
        trips = planJourney(options);
        // Validate result
        assertEquals("8.1", trips.get(0).getId().getId());
        assertEquals("4.2", trips.get(1).getId().getId());
        
        // Add timed transfer to table
        Stop stopK = new Stop();
        stopK.setId(new AgencyAndId("agency", "K"));
        Stop stopF = new Stop();
        stopF.setId(new AgencyAndId("agency", "F"));
        table.addTransferTime(stopK, stopF, null, null, null, null, StopTransfer.TIMED_TRANSFER);
        // Don't forget to also add a TimedTransferEdge
        @SuppressWarnings("deprecation")
        Vertex fromVertex = graph.getVertex("agency_K_arrive");
        @SuppressWarnings("deprecation")
        Vertex toVertex = graph.getVertex("agency_F_depart");
        TimedTransferEdge timedTransferEdge = new TimedTransferEdge(fromVertex, toVertex);
        
        // Plan journey
        trips = planJourney(options);
        // Check whether the trips are still the same
        assertEquals("8.1", trips.get(0).getId().getId());
        assertEquals("4.2", trips.get(1).getId().getId());
        
        // Now apply a real-time update: let the to-trip be early by 27600 seconds, resulting in a transfer time of 0 seconds
        @SuppressWarnings("deprecation")
        TableTripPattern pattern = ((PatternStopVertex) graph.getVertex("agency_F_agency_4.3_1_D")).getTripPattern();
        applyUpdateToTripPattern(pattern, "4.2", "F", 0, 55200, 55200, Update.Status.PREDICTION, 0);
        
        // Plan journey
        trips = planJourney(options);
        // Check whether the trips are still the same
        assertEquals("8.1", trips.get(0).getId().getId());
        assertEquals("4.2", trips.get(1).getId().getId());
        
        // Now apply a real-time update: let the to-trip be early by 27601 seconds, resulting in a transfer time of -1 seconds
        applyUpdateToTripPattern(pattern, "4.2", "F", 0, 55199, 55199, Update.Status.PREDICTION, 0);
        
        // Plan journey
        trips = planJourney(options);
        // Check whether a later second trip was taken
        assertEquals("8.1", trips.get(0).getId().getId());
        assertEquals("4.3", trips.get(1).getId().getId());
        
        // "Revert" the real-time update
        applyUpdateToTripPattern(pattern, "4.2", "F", 0, 82800, 82800, Update.Status.PREDICTION, 0);
        // Remove the timed transfer from the graph
        timedTransferEdge.detach();
        // Revert the graph, thus using the original transfer table again
        reset(graph);
    }
    
}
