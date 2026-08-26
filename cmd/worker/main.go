package worker

func main() {
	// TODO:
	// 1. build a storage.Client from config/env
	// 2. build the handlers map: one queue.Handler per queue.JobType,
	//    each a thin adapter calling normalize/parse/imgpipe/geo
	// 3. queue.Run(redisAddr, handlers)	
}