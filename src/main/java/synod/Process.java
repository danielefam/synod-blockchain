package synod;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedAbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class Process extends UntypedAbstractActor{

	private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

	public Process() {}

	public static Props createActor() {
		return Props.create(Process.class, () -> {
			return new Process();
		});
	}

	@Override
	public void onReceive(Object message) throws Throwable {

	}



}
