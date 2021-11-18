package cn.zzs.hystrix;

public class Response {
    /**
     * 响应状态，true为正常，false为异常
     *
     */
    private boolean success;

    /**
     * 响应消息
     */
    private String message;
    
    /**
     * 响应状态码
     *
     */
    private String code;

    
    public boolean isSuccess() {
        return success;
    }


    
    public void setSuccess(boolean success) {
        this.success = success;
    }


    public String getMessage() {
        return message;
    }

    
    public void setMessage(String message) {
        this.message = message;
    }

    
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Response [success=");
        builder.append(success);
        builder.append(", message=");
        builder.append(message);
        builder.append(", code=");
        builder.append(code);
        builder.append("]");
        return builder.toString();
    }



    public String getCode() {
        return code;
    }

    
    public void setCode(String code) {
        this.code = code;
    }
}   
