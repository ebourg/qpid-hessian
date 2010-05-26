/**
 * Copyright 2010 Emmanuel Bourg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.qpid.contrib.hessian;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author Emmanuel Bourg
 * @version $Revision$, $Date$
 */
class AsyncResponse<T> implements Future<T>
{
    private CountDownLatch latch = new CountDownLatch(1);
    private boolean done = false;
    private T result;
    
    public boolean cancel(boolean mayInterruptIfRunning)
    {
        return false;
    }

    public boolean isCancelled()
    {
        return false;
    }

    public boolean isDone()
    {
        return done;
    }

    public void set(T t)
    {
        if (!done) {
            result = t;
            done = true;
            latch.countDown();
        }
    }

    public T get() throws InterruptedException, ExecutionException
    {
        latch.await();
        return result;
    }

    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
        if (latch.await(timeout, unit)) {
            return result;
        } else {
            throw new TimeoutException("No response after " + timeout + " " + unit.toString().toLowerCase());
        }
    }
}
