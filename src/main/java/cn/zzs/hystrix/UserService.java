package cn.zzs.hystrix;

import java.util.Arrays;
import java.util.List;

public class UserService {
    
    private UserService () {
        super();
    }
    
    public static UserService instance() {
        return UserServiceHolder.INSTANCE;
    }
    
    private static class UserServiceHolder {
        private static final UserService INSTANCE = new UserService();
    }
    
    final List<User> users = Arrays.asList(
            new User("1", "zzs001", 18), 
            new User("2", "zzs002", 18),
            new User("3", "zzs003", 18),
            new User("4", "zzf001", 18), 
            new User("5", "zzf002", 18),
            new User("6", "zzf003", 18)
            );
    
    public DataResponse<User> getUserById(String userId) {
        try {
            Thread.sleep(500);
        } catch(InterruptedException e) {
            e.printStackTrace();
        }
        for (User user : users) {
            if (user.getId().equals(userId)) {
                return DataResponse.buildSuccess(user);
            }
        }
        return DataResponse.buildFailure("未获取到指定用户");
    }
}
